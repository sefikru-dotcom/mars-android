package ru.mars.mobile.tts;

import kotlin.jvm.functions.Function1;

/**
 * Колбэк синтеза для native-кода sherpa-onnx — с точной сигнатурой, которую ищет JNI.
 *
 * <p>Зачем отдельный Java-класс, а не Kotlin-лямбда. {@code libsherpa-onnx-jni.so}
 * находит колбэк через {@code GetMethodID} по строке {@code invoke([F)Ljava/lang/Integer;}
 * — это видно в аварийном сообщении ART:
 *
 * <pre>
 * NoSuchMethodError: no non-static method "Lk8/b;.invoke([F)Ljava/lang/Integer;"
 * </pre>
 *
 * <p>Kotlin-лямбда возвращает примитивный {@code int}, а у лямбды, слитой R8 с другими
 * классами, тип возврата становится {@code java.lang.Object} — ни один вариант не
 * совпадает с {@code Integer}, и JNI вместо исключения зовёт {@code abort()}: процесс
 * умирает с SIGABRT, приложение схлопывается.
 *
 * <p>Java разрешает объявить возврат {@code Integer} у {@code invoke}, поэтому сигнатура
 * совпадает с ожидаемой и остаётся такой при обфускации: R8 вправе переименовать класс,
 * но не вправе менять сигнатуру метода.
 */
public final class SynthesisCallback implements Function1<float[], Integer> {

    /** Логика обработки куска: {@code 0} — продолжать синтез, иначе — остановиться. */
    public interface Handler {
        int onSamples(float[] samples);
    }

    private final Handler handler;

    public SynthesisCallback(Handler handler) {
        this.handler = handler;
    }

    @Override
    public Integer invoke(float[] samples) {
        return handler.onSamples(samples);
    }
}
