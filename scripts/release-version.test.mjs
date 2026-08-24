import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { updatePackageVersion, versionFromTag } from './release-version.mjs'

test('maps final and release-candidate tags to marketplace-safe semver', () => {
  assert.equal(versionFromTag('v1.2.3'), '1.2.3')
  assert.equal(versionFromTag('v1.2.3-rc.4'), '1.2.3-rc.4')
  assert.throws(() => versionFromTag('release-1.2.3'), /Unsupported release tag/)
  assert.throws(() => versionFromTag('v1.2'), /Unsupported release tag/)
})

test('writes the derived version into the VS Code manifest', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-pilot-version-'))
  const manifestPath = path.join(directory, 'package.json')
  try {
    fs.writeFileSync(manifestPath, '{"name":"test","version":"0.0.0"}\n')
    updatePackageVersion(manifestPath, versionFromTag('v2.1.0-rc.3'))
    assert.equal(JSON.parse(fs.readFileSync(manifestPath, 'utf8')).version, '2.1.0-rc.3')
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
})
