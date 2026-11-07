import { cp, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const from = path.join(webRoot, 'dist')
const to = path.resolve(webRoot, '../app/src/main/assets/www')

await rm(to, { recursive: true, force: true })
await mkdir(to, { recursive: true })
await cp(from, to, { recursive: true })
console.log(`Synced ${from} -> ${to}`)
