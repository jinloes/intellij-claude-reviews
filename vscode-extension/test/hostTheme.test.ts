import test from 'node:test';
import assert from 'node:assert/strict';

import { classifyHostTheme } from '../src/hostTheme';

test('classifyHostTheme maps every contrast and brightness combination', () => {
    assert.equal(classifyHostTheme(false, false), 'light');
    assert.equal(classifyHostTheme(true, false), 'dark');
    assert.equal(classifyHostTheme(false, true), 'highContrastLight');
    assert.equal(classifyHostTheme(true, true), 'highContrastDark');
});
