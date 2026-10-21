//
//  HRVBridgeApp.swift
//  HRVBridge
//
//  Created by Paul Müller on 20.10.26.
//

import SwiftUI

@main
struct HRVBridgeApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if let name = Snapshots.requested {
                Snapshots.view(name)
            } else {
                ContentView()
            }
            #else
            ContentView()
            #endif
        }
    }
}
