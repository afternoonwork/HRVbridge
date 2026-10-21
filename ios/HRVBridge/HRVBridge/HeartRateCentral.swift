import CoreBluetooth
import Foundation
import HRVCore
import Observation

/// Monotonic milliseconds for packet timestamps and the session clock. Debug builds can
/// run it faster for the simulated strap; it is always 1x with a real strap.
enum Clock {
    nonisolated private static let lock = NSLock()
    nonisolated(unsafe) private static var speed = 1.0
    nonisolated(unsafe) private static var realBase: Int64 = 0
    nonisolated(unsafe) private static var virtualBase: Int64 = 0

    nonisolated static func nowMs() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return unlockedNow()
    }

    /// Continues from the current time at the new speed (monotonic across changes).
    nonisolated static func setSpeed(_ newSpeed: Double) {
        lock.lock()
        defer { lock.unlock() }
        let now = unlockedNow()
        virtualBase = now
        realBase = realMs()
        speed = newSpeed
    }

    nonisolated private static func realMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }

    nonisolated private static func unlockedNow() -> Int64 {
        virtualBase + Int64(Double(realMs() - realBase) * speed)
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
        /// Could not connect; the message is shown as is.
        case failed(String)
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
    @ObservationIgnored private var simulation: Task<Void, Never>?

    private static let heartRateService = CBUUID(string: "180D")
    private static let heartRateMeasurement = CBUUID(string: "2A37")
    private static let rememberedKey = "lastStrapId"
    /// CoreBluetooth never times out a connect; a strap held by another app can stall.
    static let connectTimeout: Duration = .seconds(15)
    static let connectTimeoutMessage = "Couldn't connect. Close other apps using the strap (e.g. Polar Flow) and try again."

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
        Task { [weak self] in
            try? await Task.sleep(for: Self.connectTimeout)
            guard let self, p === self.current, !self.isConnected else { return }
            self.log.add("BLE", "connect timeout after 15 s, cancelling")
            self.manager?.cancelPeripheralConnection(p)
            self.ended(p, "connect timeout")
            self.status = .failed(Self.connectTimeoutMessage)
        }
    }

    func disconnect() {
        if simulation != nil { return stopSimulation() }
        wantsScan = false
        manager?.stopScan()
        if let p = current { manager?.cancelPeripheralConnection(p) }
        log.add("BLE", "disconnect requested")
    }

    #if DEBUG
    /// Debug builds only: replays synthetic packets through the same path as real
    /// notifications (parser, live view, recorder, measurement). `speed` > 1 fast-forwards
    /// the shared clock, so the session timer runs faster too.
    func startSimulation(_ scenario: SimulatorScenario, speed: Double) {
        manager?.stopScan()
        wantsScan = false
        Clock.setSpeed(speed)
        let name = "Simulated strap"
        connectedName = name
        connectedId = nil
        status = .connected(name)
        log.add("SIM", "start \(scenario.title) at \(Int(speed))x")
        onConnected?()
        let packets = scenario.packets()
        let start = Clock.nowMs()
        simulation = Task { [weak self] in
            for p in packets {
                let due = start + p.timeMs
                let waitMs = Double(due - Clock.nowMs()) / speed
                if waitMs > 0 { try? await Task.sleep(for: .milliseconds(Int(waitMs))) }
                if Task.isCancelled { return }
                self?.value(p.bytes, at: due)
            }
            self?.log.add("SIM", "out of data")
        }
    }
    #endif

    private func stopSimulation() {
        simulation?.cancel()
        simulation = nil
        Clock.setSpeed(1)
        connectedName = nil
        status = .disconnected("simulation stopped")
        log.add("SIM", "stopped")
        onDisconnected?()
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
        // Powered off or resetting: CoreBluetooth does not call didDisconnectPeripheral,
        // so end the current strap here or a running measurement would hang.
        if state != .poweredOn, let p = current {
            ended(p, "Bluetooth became unavailable (state \(state.rawValue))")
        }
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
