import CoreBluetooth
import Foundation
import HRVCore
import Observation

/// Monotonic milliseconds for packet timestamps and the session clock.
enum Clock {
    nonisolated static func nowMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }
}

struct DiscoveredStrap: Identifiable, Equatable {
    let id: UUID
    var name: String
    var rssi: Int
}

/// Heart rate strap over CoreBluetooth: scan for 0x180D, connect, subscribe to 0x2A37.
/// Delegate callbacks arrive on the main queue. Every event goes to `log`.
@Observable
final class HeartRateCentral: NSObject {
    enum Status: Equatable {
        case idle
        case bluetoothUnavailable(String)
        case scanning
        case connecting(String)
        case connected(String)
        case disconnected(String)
    }

    private(set) var status: Status = .idle
    private(set) var found: [DiscoveredStrap] = []
    private(set) var latest: HeartRateMeasurement?
    private(set) var recentRr: [Double] = []
    private(set) var packetCount = 0
    private(set) var connectedName: String?
    private(set) var connectedId: UUID?

    let log: DebugLog
    /// Every 0x2A37 notification, raw, with its arrival time (`Clock.nowMs()`).
    @ObservationIgnored var onPacket: (([UInt8], Int64) -> Void)?
    /// Notifications are on.
    @ObservationIgnored var onConnected: (() -> Void)?
    @ObservationIgnored var onDisconnected: (() -> Void)?

    @ObservationIgnored private var manager: CBCentralManager?
    @ObservationIgnored private var peripherals: [UUID: CBPeripheral] = [:]
    @ObservationIgnored private var current: CBPeripheral?
    @ObservationIgnored private var wantsScan = false

    private static let heartRateService = CBUUID(string: "180D")
    private static let heartRateMeasurement = CBUUID(string: "2A37")
    private static let rememberedKey = "lastStrapId"

    init(log: DebugLog) {
        self.log = log
        super.init()
    }

    var isConnected: Bool {
        if case .connected = status { return true }
        return false
    }

    /// Starts scanning (creating the central on first use, which shows the permission prompt).
    func startScan() {
        wantsScan = true
        found = []
        if manager == nil {
            manager = CBCentralManager(delegate: self, queue: nil)
            return // scanning starts in centralManagerDidUpdateState
        }
        beginScanIfReady()
    }

    func connect(_ id: UUID) {
        guard let manager, let p = peripherals[id] else { return }
        manager.stopScan()
        wantsScan = false
        current = p
        p.delegate = self
        status = .connecting(p.name ?? "strap")
        log.add("BLE", "connect \(id) name=\(p.name ?? "?")")
        manager.connect(p, options: nil)
    }

    func disconnect() {
        wantsScan = false
        manager?.stopScan()
        if let p = current { manager?.cancelPeripheralConnection(p) }
        log.add("BLE", "disconnect requested")
    }

    private func beginScanIfReady() {
        guard wantsScan, let manager, manager.state == .poweredOn else { return }
        status = .scanning
        let remembered = UserDefaults.standard.string(forKey: Self.rememberedKey)
        log.add("BLE", "scan start, remembered=\(remembered ?? "none")")
        // A strap already connected to the phone (e.g. via another app) does not advertise.
        for p in manager.retrieveConnectedPeripherals(withServices: [Self.heartRateService]) {
            log.add("BLE", "already connected to system: \(p.identifier) name=\(p.name ?? "?")")
            remember(p, rssi: 0)
        }
        manager.scanForPeripherals(withServices: [Self.heartRateService], options: nil)
    }

    private func remember(_ p: CBPeripheral, rssi: Int) {
        let isNew = peripherals[p.identifier] == nil
        peripherals[p.identifier] = p
        let strap = DiscoveredStrap(id: p.identifier, name: p.name ?? "Heart rate strap", rssi: rssi)
        if let i = found.firstIndex(where: { $0.id == p.identifier }) {
            found[i] = strap
        } else {
            found.append(strap)
        }
        if isNew { log.add("BLE", "found \(p.identifier) name=\(p.name ?? "?") rssi=\(rssi)") }
        if p.identifier.uuidString == UserDefaults.standard.string(forKey: Self.rememberedKey), current == nil {
            connect(p.identifier)
        }
    }

    // MARK: Event handlers (main actor)

    private func stateChanged(_ state: CBManagerState) {
        log.add("BLE", "central state \(state.rawValue)")
        switch state {
        case .poweredOn: beginScanIfReady()
        case .poweredOff: status = .bluetoothUnavailable("Bluetooth is off.")
        case .unauthorized: status = .bluetoothUnavailable("Bluetooth access is not allowed. Enable it in Settings, HRV Bridge.")
        case .unsupported: status = .bluetoothUnavailable("This device does not support Bluetooth LE.")
        default: break
        }
    }

    private func connected(_ p: CBPeripheral) {
        log.add("BLE", "connected \(p.identifier), discovering services")
        p.discoverServices([Self.heartRateService])
    }

    private func ended(_ p: CBPeripheral, _ reason: String) {
        log.add("BLE", "\(reason) \(p.identifier)")
        guard p === current else { return }
        current = nil
        connectedName = nil
        connectedId = nil
        status = .disconnected(reason)
        onDisconnected?()
    }

    private func servicesDiscovered(_ p: CBPeripheral, _ error: Error?) {
        log.add("BLE", "services \(p.services?.map(\.uuid.uuidString) ?? []) error=\(error.map { "\($0)" } ?? "none")")
        guard let s = p.services?.first(where: { $0.uuid == Self.heartRateService }) else {
            return fail(p, "no heart rate service")
        }
        p.discoverCharacteristics([Self.heartRateMeasurement], for: s)
    }

    private func characteristicsDiscovered(_ p: CBPeripheral, _ s: CBService, _ error: Error?) {
        log.add("BLE", "characteristics \(s.characteristics?.map(\.uuid.uuidString) ?? []) error=\(error.map { "\($0)" } ?? "none")")
        guard let c = s.characteristics?.first(where: { $0.uuid == Self.heartRateMeasurement }) else {
            return fail(p, "no 0x2A37 characteristic")
        }
        p.setNotifyValue(true, for: c)
    }

    private func notificationState(_ p: CBPeripheral, _ c: CBCharacteristic, _ error: Error?) {
        log.add("BLE", "notify \(c.uuid.uuidString) isNotifying=\(c.isNotifying) error=\(error.map { "\($0)" } ?? "none")")
        guard c.uuid == Self.heartRateMeasurement else { return }
        guard c.isNotifying, error == nil else { return fail(p, "enabling notifications failed") }
        UserDefaults.standard.set(p.identifier.uuidString, forKey: Self.rememberedKey)
        connectedName = p.name ?? "Heart rate strap"
        connectedId = p.identifier
        status = .connected(connectedName!)
        onConnected?()
    }

    private func value(_ bytes: [UInt8], at t: Int64) {
        packetCount += 1
        let m = HeartRateParser.parse(bytes)
        latest = m
        if let rr = m?.rrIntervalsMs, !rr.isEmpty { recentRr = Array((recentRr + rr).suffix(8)) }
        log.add("RX", "t=\(t) " + DebugLog.describePacket(bytes, m))
        onPacket?(bytes, t)
    }

    private func fail(_ p: CBPeripheral, _ reason: String) {
        log.add("BLE", "failed: \(reason)")
        manager?.cancelPeripheralConnection(p)
        status = .disconnected(reason)
    }
}

// CoreBluetooth calls these on the main queue (queue: nil above), so hopping to the
// main actor with assumeIsolated is safe.
extension HeartRateCentral: CBCentralManagerDelegate, CBPeripheralDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let state = central.state
        MainActor.assumeIsolated { stateChanged(state) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let rssi = RSSI.intValue
        MainActor.assumeIsolated { remember(peripheral, rssi: rssi) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated { connected(peripheral) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let reason = "connect failed: \(error.map { "\($0)" } ?? "unknown")"
        MainActor.assumeIsolated { ended(peripheral, reason) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let reason = "disconnected: \(error.map { "\($0)" } ?? "by request")"
        MainActor.assumeIsolated { ended(peripheral, reason) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated { servicesDiscovered(peripheral, error) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated { characteristicsDiscovered(peripheral, service, error) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                                error: Error?) {
        MainActor.assumeIsolated { notificationState(peripheral, characteristic, error) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        // Timestamp on arrival, before anything else.
        let t = Clock.nowMs()
        guard characteristic.uuid == CBUUID(string: "2A37"), let data = characteristic.value else { return }
        let bytes = [UInt8](data)
        MainActor.assumeIsolated { value(bytes, at: t) }
    }
}
