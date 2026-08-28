package io.github.giuploader

import java.nio.ByteOrder

/** Hardware-specific values used by the STM32 system-memory DFU bootloader. */
data class Stm32BoardProfile(
    val id: String,
    val displayName: String,
    val flashStartAddress: Long,
    val flashSizeBytes: Long,
    val pageSizeBytes: Long,
    val defaultTransferSizeBytes: Int = 2048,
    val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    val setAddressCommand: Byte = 0x21,
    val eraseCommand: Byte = 0x41
) {
    val flashEndAddress: Long get() = flashStartAddress + flashSizeBytes
}

object BoardProfiles {
    val STM32F072_128K = Stm32BoardProfile(
        id = "stm32f072-128k",
        displayName = "STM32F072 • 128 KB",
        flashStartAddress = 0x08000000L,
        flashSizeBytes = 128L * 1024L,
        pageSizeBytes = 2L * 1024L
    )

    // USB VID/PID alone cannot identify the exact STM32 model.
    val all: List<Stm32BoardProfile> = listOf(STM32F072_128K)
}
