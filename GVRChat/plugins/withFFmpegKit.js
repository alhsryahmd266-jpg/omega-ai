const { withAppBuildGradle } = require('@expo/config-plugins');

/**
 * Expo Config Plugin: injects FFmpegKit Android dependency
 * into app/build.gradle after `expo prebuild` regenerates it.
 *
 * Honest note: FFmpegKit (arthenica/ffmpeg-kit) was archived in 2023 and
 * receives no further updates. It still works as a Maven dependency today,
 * but long-term this should be re-evaluated if Android/Gradle versions
 * move far enough forward that it breaks.
 */
module.exports = function withFFmpegKit(config) {
  return withAppBuildGradle(config, (config) => {
    if (!config.modResults.contents.includes('ffmpeg-kit-full')) {
      config.modResults.contents = config.modResults.contents.replace(
        /dependencies\s*\{/,
        `dependencies {\n    implementation("com.arthenica:ffmpeg-kit-full:6.0-2")`
      );
    }
    return config;
  });
};
