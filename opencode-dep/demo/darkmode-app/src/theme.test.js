import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveTheme } from './theme.js';

test('explicit dark wins over system', () => {
  assert.equal(resolveTheme('dark', false), 'dark');
});

test('explicit light wins over system', () => {
  assert.equal(resolveTheme('light', true), 'light');
});

test('system follows OS prefers-dark', () => {
  assert.equal(resolveTheme('system', true), 'dark');
  assert.equal(resolveTheme('system', false), 'light');
});
