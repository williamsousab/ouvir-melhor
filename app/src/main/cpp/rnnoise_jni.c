#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL
Java_br_com_ouvirmelhor_NeuralDenoiser_nativeCreate(JNIEnv *env, jclass clazz) {
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_br_com_ouvirmelhor_NeuralDenoiser_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle != 0) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}

/* Processa 480 amostras (10 ms a 48 kHz) no próprio array. Devolve a probabilidade de voz. */
JNIEXPORT jfloat JNICALL
Java_br_com_ouvirmelhor_NeuralDenoiser_nativeProcess(JNIEnv *env, jclass clazz, jlong handle,
                                                      jfloatArray buffer) {
    if (handle == 0) return 0.f;
    float tmp[FRAME];
    jfloat *p = (*env)->GetPrimitiveArrayCritical(env, buffer, NULL);
    if (p == NULL) return 0.f;
    float vad = rnnoise_process_frame((DenoiseState *) (intptr_t) handle, tmp, p);
    memcpy(p, tmp, sizeof(tmp));
    (*env)->ReleasePrimitiveArrayCritical(env, buffer, p, 0);
    return vad;
}
