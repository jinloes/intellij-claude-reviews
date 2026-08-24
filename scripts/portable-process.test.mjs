import assert from 'node:assert/strict'
import test from 'node:test'
import { gradleWrapperInvocation, npmCliInvocation } from './portable-process.mjs'

test('npm scripts invoke the npm JavaScript CLI instead of a Windows cmd shim', () => {
  const invocation = npmCliInvocation(
    ['run', 'build:test'],
    {
      nodeExecutable: 'C:\\Program Files\\nodejs\\node.exe',
      npmCli: 'C:\\Program Files\\nodejs\\node_modules\\npm\\bin\\npm-cli.js',
    },
  )

  assert.equal(invocation.command, 'C:\\Program Files\\nodejs\\node.exe')
  assert.deepEqual(invocation.args, [
    'C:\\Program Files\\nodejs\\node_modules\\npm\\bin\\npm-cli.js',
    'run',
    'build:test',
  ])
  assert.doesNotMatch(invocation.command, /\.(?:cmd|bat)$/i)
})

test('Gradle uses the wrapper main class instead of a Windows batch file', () => {
  const invocation = gradleWrapperInvocation(
    [':review-engine:test', '--tests', '*BinaryLocatorTest'],
    {
      repoRoot: 'C:\\work\\pr-pilot',
      platform: 'win32',
      javaHome: 'C:\\Java\\jdk-17',
    },
  )

  assert.equal(invocation.command, 'C:\\Java\\jdk-17\\bin\\java.exe')
  assert.equal(invocation.args[3], 'org.gradle.wrapper.GradleWrapperMain')
  assert.deepEqual(invocation.args.slice(4), [
    ':review-engine:test',
    '--tests',
    '*BinaryLocatorTest',
  ])
  assert.doesNotMatch(invocation.command, /\.(?:cmd|bat)$/i)
})
