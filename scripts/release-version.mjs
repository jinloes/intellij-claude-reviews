import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

export function versionFromTag(tag) {
  const match = /^v(\d+\.\d+\.\d+(?:-rc\.\d+)?)$/.exec(tag)
  if (!match) {
    throw new Error(`Unsupported release tag "${tag}". Expected vX.Y.Z or vX.Y.Z-rc.N.`)
  }
  return match[1]
}

export function updatePackageVersion(packagePath, version) {
  const manifest = JSON.parse(fs.readFileSync(packagePath, 'utf8'))
  manifest.version = version
  fs.writeFileSync(packagePath, `${JSON.stringify(manifest, null, 2)}\n`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [, , tag, packagePath, githubOutput, githubEnv] = process.argv
  if (!tag || !packagePath) {
    console.error('Usage: node scripts/release-version.mjs <tag> <package.json> [github-output] [github-env]')
    process.exit(2)
  }
  try {
    const version = versionFromTag(tag)
    updatePackageVersion(packagePath, version)
    if (githubOutput) fs.appendFileSync(githubOutput, `version=${version}\n`)
    if (githubEnv) fs.appendFileSync(githubEnv, `ORG_GRADLE_PROJECT_releaseVersion=${version}\n`)
    console.log(version)
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error))
    process.exit(1)
  }
}
