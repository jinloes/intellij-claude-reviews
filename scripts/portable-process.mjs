import path from 'node:path'

export function npmCliInvocation(args, options = {}) {
  const nodeExecutable = options.nodeExecutable ?? process.execPath
  const npmCli = options.npmCli ?? process.env.npm_execpath
  if (!npmCli) {
    throw new Error('npm_execpath is unavailable; run this command through an npm script.')
  }
  return { command: nodeExecutable, args: [npmCli, ...args] }
}

export function gradleWrapperInvocation(args, options = {}) {
  const platform = options.platform ?? process.platform
  const paths = platform === 'win32' ? path.win32 : path
  const repoRoot = paths.resolve(options.repoRoot ?? process.cwd())
  const javaHome = options.javaHome ?? process.env.JAVA_HOME
  const javaExecutable = javaHome
    ? paths.join(javaHome, 'bin', platform === 'win32' ? 'java.exe' : 'java')
    : platform === 'win32' ? 'java.exe' : 'java'
  return {
    command: javaExecutable,
    args: [
      '-Dorg.gradle.appname=gradlew',
      '-classpath',
      paths.join(repoRoot, 'gradle', 'wrapper', 'gradle-wrapper.jar'),
      'org.gradle.wrapper.GradleWrapperMain',
      ...args,
    ],
  }
}
