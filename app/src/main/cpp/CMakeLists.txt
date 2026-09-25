cmake_minimum_required(VERSION 3.22.1)

project("probe_core")

# Mandatory 16 KB ELF segment alignment for Android 15 & 16
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(
    probe_core
    SHARED
    probe_core.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    probe_core
    ${log-lib}
)
