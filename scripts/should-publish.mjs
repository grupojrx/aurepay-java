/**
 * Decide se br.com.aurepay:aurepay (Maven) deve publicar.
 * exit 0 = publish, 10 = skip, 1 = erro (mudou sem bump)
 */
import { createHash } from 'node:crypto'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execSync } from 'node:child_process'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const groupId = 'br.com.aurepay'
const artifactId = 'aurepay'
const userAgent = 'AurePaySDKPublish (mailto=dev@aurepay.com.br)'
const pathGroup = groupId.replaceAll('.', '/')

function walkFiles(dir, files = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)

    if (statSync(path).isDirectory()) {
      walkFiles(path, files)
    } else {
      files.push(path)
    }
  }

  return files
}

function readLocalVersion() {
  const text = readFileSync(join(root, 'pom.xml'), 'utf8')
  const match = text.match(/<artifactId>aurepay<\/artifactId>\s*<version>([^<]+)<\/version>/s)

  if (!match) {
    const fallback = text.match(/<version>([0-9][^<]*)<\/version>/)

    if (!fallback) {
      throw new Error('version missing in pom.xml')
    }

    return fallback[1]
  }

  return match[1]
}

function hashJavaSources(baseDir) {
  const hash = createHash('sha256')
  hash.update('pom.xml\0')
  hash.update('STUB\0')

  const srcDir = join(baseDir, 'src/main/java')

  if (!existsSync(srcDir)) {
    throw new Error(`src/main/java missing in ${baseDir}`)
  }

  const files = walkFiles(srcDir)
    .filter((file) => file.endsWith('.java'))
    .sort((a, b) => relative(srcDir, a).localeCompare(relative(srcDir, b)))

  for (const file of files) {
    const rel = `src/main/java/${relative(srcDir, file).replaceAll('\\', '/')}`
    hash.update(rel)
    hash.update('\0')
    hash.update(readFileSync(file, 'utf8').replaceAll('\r\n', '\n'))
    hash.update('\0')
  }

  return hash.digest('hex')
}

function extractZip(archivePath, destDir) {
  try {
    execSync(`unzip -qo "${archivePath}" -d "${destDir}"`, { stdio: 'pipe' })
    return
  } catch {
    execSync(`tar -xf "${archivePath}" -C "${destDir}"`, { stdio: 'pipe' })
  }
}

/**
 * @returns {Promise<string[]>}
 */
async function fetchMavenVersions() {
  const url = `https://repo1.maven.org/maven2/${pathGroup}/${artifactId}/maven-metadata.xml`
  const response = await fetch(url, { headers: { 'User-Agent': userAgent } })

  if (response.status === 404) {
    return []
  }

  if (!response.ok) {
    throw new Error(`Maven metadata HTTP ${response.status}`)
  }

  const xml = await response.text()
  const versions = [...xml.matchAll(/<version>([^<]+)<\/version>/g)].map((match) => match[1])

  return versions
}

const version = readLocalVersion()
const versions = await fetchMavenVersions()
const localHash = hashJavaSources(root)

if (versions.length === 0) {
  console.log(`no remote package — publish ${groupId}:${artifactId}:${version}`)
  process.exit(0)
}

const remoteVersion = versions.includes(version) ? version : versions[versions.length - 1]
const sourcesUrl = `https://repo1.maven.org/maven2/${pathGroup}/${artifactId}/${remoteVersion}/${artifactId}-${remoteVersion}-sources.jar`
const work = mkdtempSync(join(tmpdir(), 'aurepay-java-cmp-'))

try {
  const response = await fetch(sourcesUrl, {
    headers: { 'User-Agent': userAgent },
    redirect: 'follow'
  })

  if (!response.ok) {
    if (versions.includes(version)) {
      console.log(
        `${groupId}:${artifactId}:${version} already on Maven Central (no sources.jar to compare) — skip`
      )
      process.exit(10)
    }

    console.log(`remote ${remoteVersion} has no sources.jar — publish ${version}`)
    process.exit(0)
  }

  const archive = join(work, 'sources.jar')
  writeFileSync(archive, Buffer.from(await response.arrayBuffer()))
  const extractDir = join(work, 'src/main/java')
  mkdirSync(extractDir, { recursive: true })
  extractZip(archive, extractDir)

  const remoteHash = hashJavaSources(work)

  if (localHash === remoteHash) {
    console.log(
      `SDK unchanged vs ${groupId}:${artifactId}:${remoteVersion} — skip (${localHash.slice(0, 12)})`
    )
    process.exit(10)
  }

  if (versions.includes(version)) {
    console.error(
      `SDK changed, but ${groupId}:${artifactId}:${version} already on Maven Central. Bump pom.xml version.`
    )
    process.exit(1)
  }

  console.log(
    `SDK changed (${localHash.slice(0, 8)} ≠ ${remoteHash.slice(0, 8)}) — publish ${version}`
  )
  process.exit(0)
} finally {
  rmSync(work, { recursive: true, force: true })
}
