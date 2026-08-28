# GiUploader contributor instructions

## Project

GiUploader is a native Kotlin Android app that guides a user through selecting and flashing GiUCAN firmware to an STM32 board over USB DFU.

- Application ID and namespace: `io.github.giuploader`
- Minimum Android version: API 26
- Target/compile SDK: target 35, compile 37
- Android Gradle Plugin: 9.3.2
- Gradle: 9.5.0
- Kotlin support is built into AGP 9; do not add `org.jetbrains.kotlin.android`.

## Important DFU behavior

- The board is an STM32F072 with 128 KiB internal flash and 2 KiB pages.
- The bootloader is normally detected as USB VID/PID `0483:df11`.
- DFU uses USB control transfers; it does not require a bulk endpoint.
- Query the DFU functional descriptor for the fixed `wTransferSize`. Do not hard-code the DFU block size: block addressing is calculated by the bootloader from that value.
- Parse ELF32 `PT_LOAD` segments and use their physical load address (`p_paddr`, falling back to `p_vaddr` when necessary).
- Erase by address before writing. For the STM32F072, erase the affected 2 KiB pages. “Erase and Upload” means all 128 KiB (`0x08000000..0x08020000`); normal upload means only the ELF-covered range.
- Keep exact transfer-length checks and DFU status polling. Never report success after a write error or byte mismatch.
- Readback verification is best effort: STM32 read protection or a bootloader without readback support can reject `DFU_UPLOAD`. A rejected readback must be shown as “verification unavailable”, not as a byte-level verification success.
- Keep board-specific values in `Stm32BoardProfile.kt`; keep USB DFU request constants in `DfuProtocol.kt`. Do not add another MCU's flash geometry directly to `DfuUploader.kt`.
- When adding a board, create a validated `Stm32BoardProfile`, add it to `BoardProfiles`, and add explicit board selection/detection before flashing it.

## UI and flow

The wizard flow is implemented in `MainActivity.kt`: splash, firmware selection/download, USB connection/permission, erase/upload/progress, and result. Layouts are currently created programmatically. Use dp conversions for all dimensions; raw pixel sizes make button labels clip on real devices.

## Editing and validation

- Preserve unrelated user changes.
- Use `apply_patch` for source edits.
- Keep network work and USB work off the main thread.
- Do not add a desktop-only DFU dependency; the Android USB implementation is local.
- Build with the Gradle wrapper/Gradle 9.5.0 and JDK 17. A physical STM32F072 in DFU mode is required for end-to-end validation.
- Before changing DFU behavior, compare the request sequence with ST AN3156 and document any device-specific assumption.

## Safety

Firmware flashing is destructive. Do not broaden erase ranges, change target addresses, or bypass verification failures without clearly surfacing the behavior in the UI and documenting the reason.
