import { spawnSync } from 'node:child_process'

const commands = {
  eslint: ['node', ['node_modules/eslint/bin/eslint.js', ...process.argv.slice(3)]],
  prettier: [
    'node',
    ['node_modules/prettier/bin/prettier.cjs', '--check', ...process.argv.slice(3)],
  ],
  java: [
    process.platform === 'win32' ? 'mvn.cmd' : 'mvn',
    ['-B', '-f', 'backend/api/pom.xml', 'spotless:check'],
  ],
}
const command = commands[process.argv[2]]
if (!command) throw new Error('Unknown quality check')
const result = spawnSync(command[0], command[1], {
  stdio: 'inherit',
  shell: process.platform === 'win32' && process.argv[2] === 'java',
})
if (result.error) console.error(result.error.message)
process.exit(result.status ?? 1)
