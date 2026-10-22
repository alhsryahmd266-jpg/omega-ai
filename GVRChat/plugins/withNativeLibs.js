const { withAndroidManifest } = require('@expo/config-plugins');

/**
 * Expo Config Plugin: declares the vendor GPU / NPU libraries that llama.rn can use as OPTIONAL native libraries.
 *
 * Android 12+ hides non-NDK vendor libraries (Adreno OpenCL, Hexagon DSP RPC) from apps that do not declare them.
 * With android:required="false" a phone that lacks them still installs and runs the app; a phone that has them
 * lets llama.cpp use the GPU / NPU for the model.
 */
const LIBS = ['libOpenCL.so', 'libOpenCL-pixel.so', 'libcdsprpc.so'];

module.exports = function withNativeLibs(config) {
  return withAndroidManifest(config, (cfg) => {
    const app = cfg.modResults.manifest.application && cfg.modResults.manifest.application[0];
    if (!app) return cfg;
    app['uses-native-library'] = app['uses-native-library'] || [];
    for (const name of LIBS) {
      if (!app['uses-native-library'].some((e) => e.$ && e.$['android:name'] === name)) {
        app['uses-native-library'].push({ $: { 'android:name': name, 'android:required': 'false' } });
      }
    }
    return cfg;
  });
};
