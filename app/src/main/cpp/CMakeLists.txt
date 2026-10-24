cmake_minimum_required(VERSION 3.22.1)

project("localbots")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_definitions(-DLOG_TAG="LocalBotsNative")

add_library(
    native-lib
    SHARED
    native-lib.cpp
    pocket_spawn.c
    proot_executor.cpp
    framebuffer_capture.cpp
    input_injector.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    native-lib
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
