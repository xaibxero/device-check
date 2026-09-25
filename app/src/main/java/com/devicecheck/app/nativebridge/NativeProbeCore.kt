package com.devicecheck.app.nativebridge

object NativeProbeCore {

    init {
        System.loadLibrary("probe_core")
    }

    external fun auditHardwareSerials(): String
    external fun auditKernelNetwork(): String
    external fun auditBatteryRegisters(): String
    external fun auditAntiTamper(): String
    external fun auditClocks(): String
}
