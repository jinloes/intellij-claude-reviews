import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const extensionDir = path.resolve(__dirname, '..');
const repoRoot = path.resolve(extensionDir, '..');
const sourceJar = path.join(repoRoot, 'sidecar', 'build', 'libs', 'pr-pilot-sidecar.jar');
const targetDir = path.join(extensionDir, 'sidecar');
const targetJar = path.join(targetDir, 'pr-pilot-sidecar.jar');

if (!fs.existsSync(sourceJar)) {
    console.error('sidecar/build/libs/pr-pilot-sidecar.jar not found. Run `./gradlew :sidecar:bootJar` first.');
    process.exit(1);
}

fs.rmSync(targetDir, { recursive: true, force: true });
fs.mkdirSync(targetDir, { recursive: true });
fs.copyFileSync(sourceJar, targetJar);
console.log(`Staged sidecar jar into ${targetJar}`);
