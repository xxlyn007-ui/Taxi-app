const { withAndroidManifest, withMainApplication, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

function withFloatingBubble(config) {
  config = withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults.manifest;
    const app = manifest.application[0];

    if (!manifest['uses-permission']) manifest['uses-permission'] = [];
    const existing = manifest['uses-permission'].map((p) => p.$['android:name']);
    const permsToAdd = [
      'android.permission.SYSTEM_ALERT_WINDOW',
      'android.permission.FOREGROUND_SERVICE',
      'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
    ];
    permsToAdd.forEach((perm) => {
      if (!existing.includes(perm)) {
        manifest['uses-permission'].push({ $: { 'android:name': perm } });
      }
    });

    if (!app.service) app.service = [];
    const svcNames = app.service.map((s) => s.$['android:name']);
    if (!svcNames.includes('.FloatingBubbleService')) {
      app.service.push({
        $: {
          'android:name': '.FloatingBubbleService',
          'android:foregroundServiceType': 'dataSync',
          'android:exported': 'false',
        },
      });
    }
    return cfg;
  });

  config = withDangerousMod(config, [
    'android',
    (cfg) => {
      const pkgDir = path.join(
        cfg.modRequest.platformProjectRoot,
        'app/src/main/java/ru/taxiimpulse/app'
      );
      fs.mkdirSync(pkgDir, { recursive: true });

      const srcDir = path.join(cfg.modRequest.projectRoot, 'android-src');
      ['FloatingBubbleService.kt', 'FloatingBubbleModule.kt', 'FloatingBubblePackage.kt'].forEach((file) => {
        const src = path.join(srcDir, file);
        if (fs.existsSync(src)) {
          fs.copyFileSync(src, path.join(pkgDir, file));
          console.log(`[withFloatingBubble] copied ${file}`);
        } else {
          console.warn(`[withFloatingBubble] WARNING: ${src} not found`);
        }
      });
      return cfg;
    },
  ]);

  config = withMainApplication(config, (cfg) => {
    let src = cfg.modResults.contents;

    if (src.includes('FloatingBubblePackage')) {
      console.log('[withFloatingBubble] FloatingBubblePackage already present, skipping injection');
      return cfg;
    }

    // Add import right after the package declaration line — always safe.
    // FloatingBubblePackage is in the same package, so the import is redundant but harmless.
    src = src.replace(
      /^(package [^\n]+\n)/,
      '$1import ru.taxiimpulse.app.FloatingBubblePackage\n'
    );

    // ── Strategy 1: .apply {} variant (modern Expo/RN template) ─────────────────
    // Pattern: PackageList(this).packages.apply {
    //            // comments
    //          }
    // → insert add(FloatingBubblePackage()) as first statement inside the apply block,
    //   preserving indentation captured from the next line after the opening brace.
    const applyMatch = src.match(/PackageList\([^)]+\)\.packages\.apply\s*\{\n([ \t]*)/);
    if (applyMatch) {
      const innerIndent = applyMatch[1];
      src = src.replace(
        /PackageList\([^)]+\)\.packages\.apply\s*\{\n/,
        (opening) => `${opening}${innerIndent}add(FloatingBubblePackage())\n`
      );
      console.log('[withFloatingBubble] injected FloatingBubblePackage into .apply block');

    // ── Strategy 2: block form — val packages = PackageList(...) ────────────────
    } else if (src.includes('val packages = PackageList')) {
      src = src.replace(
        /([ \t]*)(val packages = PackageList[^\n]+\n)/,
        (_, indent, valLine) => `${indent}${valLine}${indent}packages.add(FloatingBubblePackage())\n`
      );
      console.log('[withFloatingBubble] injected FloatingBubblePackage via block-form insert');

    // ── Strategy 3: simple single-expr = PackageList(...).packages ──────────────
    } else if (/override fun getPackages[^=]*=\s*PackageList[^)]+\)\.packages/.test(src)) {
      const m = src.match(
        /^([ \t]*)(override fun getPackages\(\)\s*:\s*List<ReactPackage>)\s*=\s*[\s]*(PackageList\([^)]+\)\.packages)/m
      );
      if (m) {
        const [fullMatch, indent, funcDecl, pcall] = m;
        const inner = indent + '  ';
        src = src.replace(
          fullMatch,
          `${indent}${funcDecl} {\n${inner}val packages = ${pcall}\n${inner}packages.add(FloatingBubblePackage())\n${inner}return packages\n${indent}}`
        );
        console.log('[withFloatingBubble] injected FloatingBubblePackage via single-expr transform');
      }

    // ── Strategy 4: return PackageList(...) form ─────────────────────────────────
    } else if (src.includes('return PackageList')) {
      src = src.replace(
        /(\s+)(return PackageList([^\n]+))/,
        (_, ws, _ret, args) => {
          const indent = ws.match(/\n([ \t]*)/)?.[1] ?? '        ';
          return (
            `\n${indent}val packages = PackageList${args}\n` +
            `${indent}packages.add(FloatingBubblePackage())\n` +
            `${indent}return packages`
          );
        }
      );
      console.log('[withFloatingBubble] injected FloatingBubblePackage via return-PackageList transform');

    } else {
      console.warn('[withFloatingBubble] WARNING: could not find getPackages injection point');
      console.warn('[withFloatingBubble] File snippet:\n' + src.slice(0, 2000));
    }

    cfg.modResults.contents = src;
    return cfg;
  });

  return config;
}

module.exports = withFloatingBubble;
