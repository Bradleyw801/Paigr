// Playwright, wherever it is: PLAYWRIGHT=<path to its package>, else what Node finds, else this sandbox's copy.
const where = process.env.PLAYWRIGHT || ["playwright", "/opt/npm-tools/node_modules/playwright"].find(p => {
  try { require.resolve(p); return true; } catch { return false; }
}) || "playwright";
module.exports = require(where);
