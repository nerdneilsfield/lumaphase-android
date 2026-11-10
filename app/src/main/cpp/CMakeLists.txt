# Builds the single native bridge for the EGL frame timestamp extension.
#
# It links only against the platform libEGL that ships with the NDK; nothing is downloaded
# and no third-party source is used. The extension entry points themselves are resolved at
# runtime with eglGetProcAddress, because the stub libEGL does not export them.
cmake_minimum_required(VERSION 3.22.1)
project(optc_egl_timestamps C)

add_library(optc_egl_timestamps SHARED egl_frame_timestamps.c)

target_link_libraries(optc_egl_timestamps EGL)

# Pure C: do not link a C++ runtime.
set_target_properties(optc_egl_timestamps PROPERTIES
        LINKER_LANGUAGE C
        C_STANDARD 11
        C_STANDARD_REQUIRED ON)
