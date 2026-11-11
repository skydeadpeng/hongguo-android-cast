// The desktop module calls require('axios') as a function, not an ESM namespace.
module.exports = require('./axios-native.mjs').default;
