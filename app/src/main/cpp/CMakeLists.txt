cmake_minimum_required(VERSION 3.22.1)

project("probe_core")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(
    probe_core
    SHARED
    probe_core.cpp
)

find_library(log-lib log)

target_link_libraries(
    probe_core
    ${log-lib}
)
