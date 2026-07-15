export type HostTheme = 'light' | 'dark' | 'highContrastLight' | 'highContrastDark';

export function classifyHostTheme(dark: boolean, highContrast: boolean): HostTheme {
    if (highContrast) return dark ? 'highContrastDark' : 'highContrastLight';
    return dark ? 'dark' : 'light';
}
