> This software was written entirely using Codex Luna. No human wrote any line of code.

# GiUploader

Native Android app for flashing [GiUCAN](https://github.com/anegrin/GiUCAN) firmware to an STM32F072 board over USB DFU.

GiUploader provides a guided wizard so firmware updates can be performed from an Android device without desktop-only flashing tools. Firmware is downloaded from the latest [GiUCAN GitHub release](https://github.com/anegrin/GiUCAN/releases/latest) and written directly through Android USB Host APIs.

This project was created using Codex Luna.

## Features

- Guided firmware upload wizard
- SLCAN, C1CAN, and BHCAN firmware selection
- Automatic download from the latest GiUCAN GitHub release
- Firmware loading from device local storage
- GitHub SHA-256 verification before firmware can be flashed
- Release information dialog with name, publication date, and notes
- STM32 DFU detection and USB permission handling
- Page erase and upload progress
- Dark STM32-inspired splash screen and app icon

## Requirements

- Android device with USB host/OTG support
- USB OTG adapter and a data-capable USB cable
- STM32F072 board with its factory USB DFU bootloader
- Android Studio with JDK 17

The project uses application ID `io.github.giuploader`, Android Gradle Plugin 9.3.2, Gradle 9.5.0, and compile SDK 37.

## Build

Open the project in Android Studio, select the Gradle wrapper as the Gradle distribution, and sync the project. Then run the `app` configuration on an Android device.

The app requires the `INTERNET` permission to download releases and the USB host feature to communicate with the board.

## Usage

1. Select a release. Use the circular info button beside the release selector to view its name, publication date, and notes.
2. Select a firmware variant, or choose **Pick firmware file** below the `-- OR --` separator to load an ELF file from device storage. Downloaded release assets are SHA-256 verified against the digest returned by GitHub before continuing.
3. Put the STM32F072 board in DFU mode and connect it over USB.
4. Choose **Upload** or **Erase and Upload**.
5. Wait while GiUploader erases, writes, and finalizes the firmware.

Local firmware files are passed through the same ELF32 load-segment parsing and flash-range checks as downloaded firmware. The selected file is read locally and is not GitHub digest-verified.

### Upload modes

**Upload** erases only the 2 KB pages covered by the selected ELF firmware.

**Erase and Upload** erases the full 128 KB STM32F072 flash range before writing.

The app queries the DFU USB descriptor for the bootloader transfer size instead of assuming a fixed write block size. ELF32 `PT_LOAD` segments are written using their physical load addresses.

## Troubleshooting

- If the board is not detected, enter DFU mode before connecting the USB cable and accept the Android USB permission prompt.
- If flashing fails, power-cycle the board and re-enter DFU mode before retrying.
- If the board does not boot after a successful transfer, check the selected firmware variant, flash protection settings, ELF load address, and BOOT0/DFU state.
- Read Unprotect operations should be performed with appropriate STM32 tooling; they normally erase flash.

## Project structure

- `MainActivity.kt` — wizard screens, firmware selection, USB detection, and progress UI
- `FirmwareRepository.kt` — GitHub release lookup and firmware download
- `DfuUploader.kt` — STM32 DFU protocol, ELF parsing, erase, and write
- `Stm32BoardProfile.kt` — board-specific flash geometry and DFU settings
- `DfuProtocol.kt` — generic USB DFU request constants
- `ic_chip.xml` — app icon

## License

GiUploader is licensed under [CC BY-NC 4.0](LICENSE.md). The linked GiUCAN repository and downloaded firmware assets may have separate licensing terms.
