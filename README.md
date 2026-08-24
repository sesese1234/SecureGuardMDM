#  The Android Key (Abloq MDM & SecureGuard MDM) - Version 0.5.0

# 4,053 Downloads 
(updated every two weeks)

A Bloq is an Android Mobile Device Management (MDM) client that enforces system policies, restricts application usage, runs a lockdown kiosk mode, and manages network security filters.

Originally named Abloq MDM, the project was rebranded to The Android Key in version 0.4.2.

GitHub Repository: https://github.com/sesese1234/SecureGuardMDM
Website: https://cfopuser.github.io/A-bloq-site/
# Cummunity thread:  https://mitmachim.top/post/974945
(Note: the thread is in hebrew, but you can ask in english, we got you!)

## Features

### 1. Device Policy Enforcement (Device Owner)
* Enforces system-level rules via Android's `DevicePolicyManager` (DPM).
* Disables factory resets, system setting modifications, and developer options.
* Prevents app uninstallation by maintaining active administration.

### 2. Kiosk Launcher Mode
* Acts as the default system home launcher to control user navigation.
* Disables recent tasks, notifications, and split-screen mode via task pinning (`lockTaskMode="if_whitelisted"`).
* Intercepts standard web links (`http`, `https`) to prevent open browsing.

### 3. User-Space VPN & Firewall
* Runs a local VPN service (`BlockerVpnService`) to inspect and filter network traffic.
* Blocks unauthorized network connections.
* Works with external filters like Netfree via `NetfreeMonitorService`.

### 4. App Blocker
* Restricts access to target apps by suspending them (greying them out).
* Prevents suspended apps from being launched or showing active notifications.

### 5. Service Watchdog
* Automatically starts on device boot (`BootCompletedReceiver`).
* Periodically checks and restarts background services via `ServiceWatchdogJob` to ensure continuous policy enforcement.

## Technical Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose
* **Dependency Injection:** Dagger Hilt
* **Database:** Room (for policy configuration)
* **APIs:** Android Device Administration, VpnService

## Setup

### Prerequisites
* Android SDK 28 to 34
* JDK 21 (for building the app)
* Android Studio

### Installation & Provisioning
To enable full system control and policy enforcement, the app must be set as the Device Owner.

#### Option A: Web Installer (Recommended)
Connect your target device via USB and use the web installer to automate the process:

Web Installer: https://cfopuser.github.io/a-bloq-installer/

#### Option B: Manual ADB Installation
If you prefer to provision the device manually using the command line:


1. **Mandatory: Remove User Accounts**
   You **must remove all accounts** (Google, WhatsApp, etc.) from the device under *Settings > Passwords & Accounts* before running the provision command. If any active accounts remain, the device owner assignment will fail. Alternatively, factory reset the device before installation.
2. Set the app as the Device Owner via ADB:
   ```bash
   adb shell dpm set-device-owner com.secureguard.mdm/.SecureGuardDeviceAdminReceiver
   ```



## Version History

* **v0.4.6:** MDM core with launcher, app suspension, and VPN firewall.
* **v0.5.0:** Refined policy execution, optimized VPN configurations, and updated UI screens.

## License & Contributions

This project have no License right now.
feel free!

### Disclaimer
This software utilizes powerful device policy and network inspection permissions. 
we are taking zero responsiblity for any damages this software may cause.
