# Copyright 2026 Christian Kierdorf
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0

# Build configuration of the bundled NCNN: CPU only, single precision only, and only the layers
# the shipped models use. Include this file before add_subdirectory(<ncnn>).
#
# NCNN_SOURCE_DIR must point to the NCNN checkout.

function(makeacopy_ncnn_set name value)
    set(${name} ${value} CACHE BOOL "" FORCE)
endfunction()

makeacopy_ncnn_set(NCNN_SHARED_LIB OFF)
makeacopy_ncnn_set(NCNN_INSTALL_SDK OFF)
makeacopy_ncnn_set(NCNN_BUILD_TOOLS OFF)
makeacopy_ncnn_set(NCNN_BUILD_EXAMPLES OFF)
makeacopy_ncnn_set(NCNN_BUILD_BENCHMARK OFF)
makeacopy_ncnn_set(NCNN_BUILD_TESTS OFF)
makeacopy_ncnn_set(NCNN_PYTHON OFF)
makeacopy_ncnn_set(NCNN_C_API OFF)
makeacopy_ncnn_set(NCNN_VULKAN OFF)
makeacopy_ncnn_set(NCNN_BENCHMARK OFF)

# The app hands over float tensors and never asks NCNN to touch pixels.
makeacopy_ncnn_set(NCNN_PIXEL OFF)
makeacopy_ncnn_set(NCNN_PIXEL_ROTATE OFF)
makeacopy_ncnn_set(NCNN_PIXEL_AFFINE OFF)
makeacopy_ncnn_set(NCNN_PIXEL_DRAWING OFF)
makeacopy_ncnn_set(NCNN_SIMPLEOCV OFF)

# Single precision is the only arithmetic the app allows: half precision changes recognised text.
# Leaving the reduced-precision code paths out also keeps the library small.
makeacopy_ncnn_set(NCNN_INT8 OFF)
makeacopy_ncnn_set(NCNN_BF16 OFF)
makeacopy_ncnn_set(NCNN_WEIGHT_QUANT OFF)
makeacopy_ncnn_set(NCNN_ARM82 OFF)
makeacopy_ncnn_set(NCNN_ARM82DOT OFF)
makeacopy_ncnn_set(NCNN_ARM82FP16FML OFF)
makeacopy_ncnn_set(NCNN_ARM84BF16 OFF)
makeacopy_ncnn_set(NCNN_ARM84I8MM OFF)
makeacopy_ncnn_set(NCNN_ARM86SVE OFF)
makeacopy_ncnn_set(NCNN_ARM86SVE2 OFF)
makeacopy_ncnn_set(NCNN_ARM86SVEBF16 OFF)
makeacopy_ncnn_set(NCNN_ARM86SVEI8MM OFF)
makeacopy_ncnn_set(NCNN_ARM86SVEF32MM OFF)

# x86 only runs on emulators: keep the common instruction sets, drop the rare and large ones.
makeacopy_ncnn_set(NCNN_AVX512 OFF)
makeacopy_ncnn_set(NCNN_AVXVNNI OFF)
makeacopy_ncnn_set(NCNN_AVXVNNIINT8 OFF)
makeacopy_ncnn_set(NCNN_AVXVNNIINT16 OFF)
makeacopy_ncnn_set(NCNN_AVXNECONVERT OFF)
makeacopy_ncnn_set(NCNN_XOP OFF)
makeacopy_ncnn_set(NCNN_FMA4 OFF)

# Layers of the shipped models (see app/src/main/assets/**/*.ncnn.param) ...
set(MAKEACOPY_NCNN_MODEL_LAYERS
    Input Convolution ConvolutionDepthWise Deconvolution BinaryOp Concat Gemm HardSigmoid HardSwish
    InnerProduct Interp LayerNorm MultiHeadAttention Permute Pooling Reshape Sigmoid Softmax Split
    Squeeze Swish)
# ... and the layers NCNN creates on its own behalf for them: borders, layout changes, fused
# activations.
set(MAKEACOPY_NCNN_HELPER_LAYERS Padding Crop Packing Cast Flatten ReLU Clip)

# Every other layer NCNN knows is switched off. The list is read from NCNN itself, so a newer NCNN
# with more layers stays reduced without touching this file.
file(STRINGS "${NCNN_SOURCE_DIR}/src/CMakeLists.txt" _ncnn_layer_lines REGEX "^ncnn_add_layer\\(")
set(_kept "")
foreach(_line IN LISTS _ncnn_layer_lines)
    string(REGEX REPLACE "^ncnn_add_layer\\(([A-Za-z0-9_]+).*" "\\1" _layer "${_line}")
    string(TOLOWER "${_layer}" _lower)
    if(_layer IN_LIST MAKEACOPY_NCNN_MODEL_LAYERS OR _layer IN_LIST MAKEACOPY_NCNN_HELPER_LAYERS)
        makeacopy_ncnn_set(WITH_LAYER_${_lower} ON)
        list(APPEND _kept ${_layer})
    else()
        makeacopy_ncnn_set(WITH_LAYER_${_lower} OFF)
    endif()
endforeach()
foreach(_layer IN LISTS MAKEACOPY_NCNN_MODEL_LAYERS MAKEACOPY_NCNN_HELPER_LAYERS)
    if(NOT _layer IN_LIST _kept)
        message(FATAL_ERROR "NCNN has no layer named ${_layer}; check ncnn_config.cmake")
    endif()
endforeach()
