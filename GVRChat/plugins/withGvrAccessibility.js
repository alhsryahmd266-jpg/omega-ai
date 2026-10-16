/**
 * Config plugin: copies the accessibility config into the app's own res folder
 * and registers the service in the app manifest during prebuild. This makes sure
 * Android lists "GVR Agent" under Accessibility settings.
 */
const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

const ACCESSIBILITY_XML = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="300"
    android:description="@string/gvr_a11y_description" />
`;

const STRINGS_XML = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="gvr_a11y_description">GVR Agent reads the screen of apps you allow and taps or types only there. Banking and payment apps are never touched.</string>
</resources>
`;

const SERVICE = 'expo.modules.terminal.GvrAccessibilityService';

module.exports = function withGvrAccessibility(config) {
  config = withDangerousMod(config, ['android', async cfg => {
    const root = path.join(cfg.modRequest.platformProjectRoot, 'app/src/main/res');
    fs.mkdirSync(path.join(root, 'xml'), { recursive: true });
    fs.writeFileSync(path.join(root, 'xml/gvr_accessibility.xml'), ACCESSIBILITY_XML);
    fs.mkdirSync(path.join(root, 'values'), { recursive: true });
    fs.writeFileSync(path.join(root, 'values/gvr_strings.xml'), STRINGS_XML);
    return cfg;
  }]);

  config = withAndroidManifest(config, cfg => {
    const app = cfg.modResults.manifest.application[0];
    app.service = app.service || [];
    if (!app.service.find(s => s.$ && s.$['android:name'] === SERVICE)) {
      app.service.push({
        $: {
          'android:name': SERVICE,
          'android:exported': 'true',
          'android:label': 'GVR Agent',
          'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
        },
        'intent-filter': [{ action: [{ $: { 'android:name': 'android.accessibilityservice.AccessibilityService' } }] }],
        'meta-data': [{ $: { 'android:name': 'android.accessibilityservice', 'android:resource': '@xml/gvr_accessibility' } }],
      });
    }
    return cfg;
  });
  return config;
};
