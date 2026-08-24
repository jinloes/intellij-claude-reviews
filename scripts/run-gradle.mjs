import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { gradleWrapperInvocation } from './portable-process.mjs'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const invocation = gradleWrapperInvocation(process.argv.slice(2), { repoRoot })
const result = spawnSync(invocation.command, invocation.args, {
  cwd: repoRoot,
  stdio: 'inherit',
  shell: false,
})
if (result.error) {
  console.error(result.error.message)
}
process.exit(result.status ?? 1)
