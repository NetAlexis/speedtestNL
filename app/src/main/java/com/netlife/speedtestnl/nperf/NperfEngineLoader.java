package com.netlife.speedtestnl.nperf;

import android.content.Context;
import android.util.Log;

import com.netlife.speedtestnl.BuildConfig;

import java.lang.reflect.Constructor;

/** Loads the application adapter that wraps the proprietary nPerf SDK. */
public final class NperfEngineLoader {

    private static final String TAG = "SpeedtestNL-nPerfSDK";

    private NperfEngineLoader() { }

    public static NperfEngine load(Context context) {
        String adapterClassName = BuildConfig.NPERF_SDK_ADAPTER_CLASS;
        try {
            Class<?> adapterClass = Class.forName(adapterClassName);
            Object instance = instantiate(adapterClass, context.getApplicationContext());
            if (!(instance instanceof NperfEngine)) {
                return new UnavailableEngine(
                    "El adaptador " + adapterClassName +
                    " no implementa NperfEngine.");
            }
            NperfEngine engine = (NperfEngine) instance;
            Log.i(TAG, "Adaptador nPerf cargado: " + adapterClassName);
            return engine;
        } catch (ClassNotFoundException error) {
            Log.w(TAG, "Adaptador nPerf no instalado: " + adapterClassName);
            return new UnavailableEngine(
                "Falta el AAR privado y el adaptador nPerf autorizado.");
        } catch (Throwable error) {
            Log.e(TAG, "No se pudo crear el adaptador nPerf", error);
            return new UnavailableEngine(
                "No se pudo inicializar el adaptador nPerf: " +
                safeMessage(error));
        }
    }

    private static Object instantiate(Class<?> adapterClass, Context context)
            throws Exception {
        try {
            Constructor<?> constructor =
                adapterClass.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            return constructor.newInstance(context);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> constructor = adapterClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message.trim();
    }

    private static final class UnavailableEngine implements NperfEngine {
        private final String reason;

        UnavailableEngine(String reason) {
            this.reason = reason;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String getUnavailableReason() {
            return reason;
        }

        @Override
        public void start(Request request, Listener listener) {
            if (listener != null) {
                listener.onError("SDK_NOT_AVAILABLE", reason, null);
            }
        }

        @Override
        public void cancel() { }

        @Override
        public void release() { }
    }
}
