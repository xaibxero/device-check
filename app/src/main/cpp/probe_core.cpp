#include <jni.h>
#include <string>
#include <sstream>
#include <vector>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/system_properties.h>
#include <time.h>
#include <dirent.h>

static std::string read_sysfs_raw(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "";

    char buffer[4096];
    std::string result;
    ssize_t bytes_read;
    while ((bytes_read = read(fd, buffer, sizeof(buffer) - 1)) > 0) {
        buffer[bytes_read] = '\0';
        result.append(buffer, bytes_read);
    }
    close(fd);
    
    // Trim trailing whitespace/newlines
    if (!result.empty()) {
        size_t last = result.find_last_not_of(" \n\r\t");
        if (last != std::string::npos) result = result.substr(0, last + 1);
    }
    return result;
}

static std::string get_bionic_prop(const char* key) {
    char val[PROP_VALUE_MAX] = {0};
    __system_property_get(key, val);
    return std::string(val);
}

// 1. Audit Hardware Serials & Silicon Registers
extern "C" JNIEXPORT jstring JNICALL
Java_com_devicecheck_app_nativebridge_NativeProbeCore_auditHardwareSerials(JNIEnv* env, jobject /* this */) {
    std::ostringstream out;

    std::string ufs_serial = read_sysfs_raw("/sys/block/sda/device/serial");
    std::string emmc_cid = read_sysfs_raw("/sys/block/mmcblk0/device/cid");
    std::string usb_serial = read_sysfs_raw("/sys/class/android_usb/android0/iSerial");
    std::string soc_machine = read_sysfs_raw("/sys/devices/soc0/machine");
    std::string soc_id = read_sysfs_raw("/sys/devices/soc0/soc_id");
    
    std::string bionic_serial = get_bionic_prop("ro.serialno");
    std::string boot_serial = get_bionic_prop("ro.boot.serialno");
    std::string baseband = get_bionic_prop("gsm.version.baseband");
    std::string ril_imei = get_bionic_prop("ril.gsm.imei");

    out << "Bionic_Serial=" << (bionic_serial.empty() ? "RESTRICTED" : bionic_serial) << "\n"
        << "Boot_Serial=" << (boot_serial.empty() ? "RESTRICTED" : boot_serial) << "\n"
        << "UFS_Chip_Serial=" << (ufs_serial.empty() ? "N/A" : ufs_serial) << "\n"
        << "eMMC_CID=" << (emmc_cid.empty() ? "N/A" : emmc_cid) << "\n"
        << "USB_iSerial=" << (usb_serial.empty() ? "N/A" : usb_serial) << "\n"
        << "SoC_Machine=" << (soc_machine.empty() ? "N/A" : soc_machine) << " [ID:" << soc_id << "]\n"
        << "Baseband_Version=" << (baseband.empty() ? "N/A" : baseband) << "\n"
        << "RIL_Prop_IMEI=" << (ril_imei.empty() ? "RESTRICTED_BY_SELINUX" : ril_imei);

    return env->NewStringUTF(out.str().c_str());
}

// 2. Audit Kernel Routing & Virtual Adapters
extern "C" JNIEXPORT jstring JNICALL
Java_com_devicecheck_app_nativebridge_NativeProbeCore_auditKernelNetwork(JNIEnv* env, jobject /* this */) {
    std::string route_data = read_sysfs_raw("/proc/net/route");
    std::istringstream stream(route_data);
    std::string line;
    std::string default_iface = "NONE";
    bool vpn_found = false;

    while (std::getline(stream, line)) {
        std::istringstream linestream(line);
        std::string iface, destination, gateway;
        linestream >> iface >> destination >> gateway;
        if (destination == "00000000") {
            default_iface = iface;
            if (iface.find("tun") != std::string::npos ||
                iface.find("wg") != std::string::npos ||
                iface.find("dummy") != std::string::npos ||
                iface.find("p2p") != std::string::npos) {
                vpn_found = true;
            }
        }
    }

    std::string arp_data = read_sysfs_raw("/proc/net/arp");
    int arp_entries = 0;
    std::istringstream arp_stream(arp_data);
    while (std::getline(arp_stream, line)) arp_entries++;

    std::ostringstream out;
    out << "DefaultGatewayIface=" << default_iface << "\n"
        << "KernelTunnelFlag=" << (vpn_found ? "VIRTUAL_TUNNEL_ACTIVE" : "PHYSICAL_ROUTE") << "\n"
        << "ArpTableEntries=" << (arp_entries > 1 ? arp_entries - 1 : 0);

    return env->NewStringUTF(out.str().c_str());
}

// 3. Audit Battery Fuel-Gauge Micro-Registers (/sys/class/power_supply/battery/uevent)
extern "C" JNIEXPORT jstring JNICALL
Java_com_devicecheck_app_nativebridge_NativeProbeCore_auditBatteryRegisters(JNIEnv* env, jobject /* this */) {
    std::string uevent = read_sysfs_raw("/sys/class/power_supply/battery/uevent");
    if (uevent.empty()) return env->NewStringUTF("BATTERY_UEVENT_RESTRICTED");

    std::istringstream stream(uevent);
    std::string line;
    std::string current_now = "N/A", voltage_now = "N/A", temp = "N/A", charge = "N/A";

    while (std::getline(stream, line)) {
        if (line.rfind("POWER_SUPPLY_CURRENT_NOW=", 0) == 0) current_now = line.substr(25);
        if (line.rfind("POWER_SUPPLY_VOLTAGE_NOW=", 0) == 0) voltage_now = line.substr(25);
        if (line.rfind("POWER_SUPPLY_TEMP=", 0) == 0) temp = line.substr(18);
        if (line.rfind("POWER_SUPPLY_CHARGE_COUNTER=", 0) == 0) charge = line.substr(28);
    }

    std::ostringstream out;
    out << "CurrentDraw=" << current_now << "uA | Voltage=" << voltage_now << "uV | Temp=" << temp << " (0.1C) | ChargeCounter=" << charge;
    return env->NewStringUTF(out.str().c_str());
}

// 4. Audit In-Memory Hooks & TracerPid (/proc/self/maps & /proc/self/status)
extern "C" JNIEXPORT jstring JNICALL
Java_com_devicecheck_app_nativebridge_NativeProbeCore_auditAntiTamper(JNIEnv* env, jobject /* this */) {
    std::string maps = read_sysfs_raw("/proc/self/maps");
    std::istringstream stream(maps);
    std::string line;
    std::vector<std::string> detected;

    while (std::getline(stream, line)) {
        if (line.find("zygisk") != std::string::npos ||
            line.find("vector") != std::string::npos ||
            line.find("lspd") != std::string::npos ||
            line.find("edxposed") != std::string::npos ||
            line.find("sandhook") != std::string::npos ||
            line.find("frida") != std::string::npos ||
            line.find("riru") != std::string::npos) {
            
            size_t p = line.find('/');
            if (p != std::string::npos) {
                std::string path = line.substr(p);
                if (std::find(detected.begin(), detected.end(), path) == detected.end()) {
                    detected.push_back(path);
                }
            }
        }
    }

    std::string status = read_sysfs_raw("/proc/self/status");
    int tracer_pid = 0;
    std::istringstream sstream(status);
    while (std::getline(sstream, line)) {
        if (line.rfind("TracerPid:", 0) == 0) {
            std::istringstream(line.substr(10)) >> tracer_pid;
            break;
        }
    }

    std::ostringstream out;
    out << "TracerPid=" << tracer_pid << "\n"
        << "HookCount=" << detected.size();
    for (const auto& h : detected) out << "\n-> " << h;

    return env->NewStringUTF(out.str().c_str());
}

// 5. Audit Temporal Monotonic Clocks to Detect Clock Skew
extern "C" JNIEXPORT jstring JNICALL
Java_com_devicecheck_app_nativebridge_NativeProbeCore_auditClocks(JNIEnv* env, jobject /* this */) {
    struct timespec ts_boot{}, ts_real{}, ts_mono{};
    clock_gettime(CLOCK_BOOTTIME, &ts_boot);
    clock_gettime(CLOCK_REALTIME, &ts_real);
    clock_gettime(CLOCK_MONOTONIC, &ts_mono);

    std::string tz_prop = get_bionic_prop("persist.sys.timezone");

    std::ostringstream out;
    out << "BootTimeSec=" << ts_boot.tv_sec << "\n"
        << "RealTimeSec=" << ts_real.tv_sec << "\n"
        << "MonotonicSec=" << ts_mono.tv_sec << "\n"
        << "SystemTimezoneProp=" << (tz_prop.empty() ? "N/A" : tz_prop);

    return env->NewStringUTF(out.str().c_str());
}
