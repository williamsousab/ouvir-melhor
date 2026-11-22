cmake_minimum_required(VERSION 3.22.1)
project(ouvirmelhor_native C)

# RNNoise (Xiph.Org / Mozilla, licença BSD) — rede neural de redução de ruído
add_library(rnnoise_jni SHARED
        rnnoise_jni.c
        rnnoise/src/denoise.c
        rnnoise/src/rnn.c
        rnnoise/src/rnn_data.c
        rnnoise/src/pitch.c
        rnnoise/src/kiss_fft.c
        rnnoise/src/celt_lpc.c)

target_include_directories(rnnoise_jni PRIVATE rnnoise/include rnnoise/src)
target_compile_options(rnnoise_jni PRIVATE -O3 -w)
target_link_libraries(rnnoise_jni m)
