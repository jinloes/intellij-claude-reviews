import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

type Rgb = [number, number, number]

function hslToRgb(value: string): Rgb {
  const [hue, saturation, lightness] = value.split(/\s+/).map(Number.parseFloat)
  const s = saturation / 100
  const l = lightness / 100
  const chroma = (1 - Math.abs(2 * l - 1)) * s
  const part = hue / 60
  const x = chroma * (1 - Math.abs((part % 2) - 1))
  const [r, g, b]: Rgb = part < 1 ? [chroma, x, 0]
    : part < 2 ? [x, chroma, 0]
      : part < 3 ? [0, chroma, x]
        : part < 4 ? [0, x, chroma]
          : part < 5 ? [x, 0, chroma]
            : [chroma, 0, x]
  const match = l - chroma / 2
  return [r + match, g + match, b + match]
}

function luminance([r, g, b]: Rgb): number {
  const linear = [r, g, b].map((channel) =>
    channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  )
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrast(first: string, second: string): number {
  const values = [luminance(hslToRgb(first)), luminance(hslToRgb(second))].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

function darkToken(name: string): string {
  const css = readFileSync(new URL('../index.css', import.meta.url), 'utf8')
  const darkBlock = css.match(/\.dark\s*\{([\s\S]*?)\n\s*\}/)?.[1]
  const value = darkBlock?.match(new RegExp(`--${name}:\\s*([^;]+);`))?.[1].trim()
  if (!value) throw new Error(`Missing dark --${name} token`)
  return value
}

void test('dark primary tokens keep normal control labels and accent text at WCAG AA contrast', () => {
  const primary = darkToken('primary')
  const primaryForeground = darkToken('primary-foreground')
  const background = darkToken('background')

  assert.ok(contrast(primaryForeground, primary) >= 4.5)
  assert.ok(contrast(primary, background) >= 4.5)
})
