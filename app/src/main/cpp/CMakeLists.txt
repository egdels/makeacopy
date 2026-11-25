# Copyright 2026 Christian Kierdorf
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

# libmakeacopy_ncnn.so: the reduced NCNN (statically linked) plus the JNI bridge the app talks to.
# Built by scripts/build_ncnn_android.sh, one library per ABI.

cmake_minimum_required(VERSION 3.22)
project(makeacopy_ncnn CXX)

if(NOT DEFINED NCNN_SOURCE_DIR)
    set(NCNN_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/ncnn")
endif()
include("${CMAKE_CURRENT_SOURCE_DIR}/ncnn_config.cmake")
add_subdirectory("${NCNN_SOURCE_DIR}" ncnn EXCLUDE_FROM_ALL)

add_library(makeacopy_ncnn SHARED ncnn_jni.cpp)
target_compile_options(makeacopy_ncnn PRIVATE -fno-rtti -fno-exceptions -fvisibility=hidden
                                              -ffunction-sections -fdata-sections)
target_link_libraries(makeacopy_ncnn PRIVATE ncnn android log)
target_link_options(makeacopy_ncnn PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
                                           -Wl,--build-id=none -Wl,-z,max-page-size=16384)
