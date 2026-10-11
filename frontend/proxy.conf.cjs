// Same-origin browser requests keep credentials on the Gateway boundary.
const target = process.env['NOTIFYHUB_GATEWAY_URL'] || 'http://127.0.0.1:8080';
module.exports = { '/api/**': { target, changeOrigin: true } };
