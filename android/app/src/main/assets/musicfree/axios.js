(function (module, exports, require) {
  'use strict';

  function stringifyParams(params) {
    if (!params) return '';
    var parts = [];
    function add(key, value) {
      if (value === null || value === undefined) return;
      if (Array.isArray(value)) {
        value.forEach(function (v, i) { add(key + '[' + i + ']', v); });
      } else if (typeof value === 'object') {
        Object.keys(value).forEach(function (k) { add(key + '[' + k + ']', value[k]); });
      } else {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
      }
    }
    Object.keys(params).forEach(function (k) { add(k, params[k]); });
    return parts.join('&');
  }

  function buildNativeConfig(config) {
    config = config || {};
    var method = (config.method || 'GET').toUpperCase();
    var url = config.url || '';
    var params = config.params;
    if (params) {
      var query = stringifyParams(params);
      if (query) url += (url.indexOf('?') >= 0 ? '&' : '?') + query;
    }
    var headers = {};
    var src = config.headers;
    if (src) {
      Object.keys(src).forEach(function (k) { headers[k.toLowerCase()] = src[k]; });
    }
    var data = config.data;
    if (data !== undefined && data !== null && typeof data === 'object' && !Array.isArray(data)) {
      var contentType = headers['content-type'] || '';
      if (contentType.toLowerCase().indexOf('application/x-www-form-urlencoded') >= 0) {
        data = stringifyParams(data);
      } else {
        data = JSON.stringify(data);
        if (!headers['content-type']) headers['content-type'] = 'application/json';
      }
    }
    return { method: method, url: url, headers: headers, data: data, responseType: config.responseType };
  }

  function parseBody(body, headers, responseType) {
    if (responseType === 'arraybuffer') return body;
    if (typeof body !== 'string' || body.length === 0) return body;
    var ct = (headers && headers['content-type']) || '';
    if (ct.indexOf('json') >= 0 || ct.indexOf('javascript') >= 0) {
      try { return JSON.parse(body); } catch (e) { return body; }
    }
    var first = body.charAt(0);
    if (first === '{' || first === '[') {
      try { return JSON.parse(body); } catch (e2) { return body; }
    }
    return body;
  }

  function request(config) {
    var nativeConfig = buildNativeConfig(config);
    return __wwHttp(nativeConfig).then(function (res) {
      if (res.status >= 400) {
        var err = new Error('Request failed with status code ' + res.status);
        err.response = {
          status: res.status,
          statusText: res.statusText || '',
          headers: res.headers || {},
          data: parseBody(res.body, res.headers, nativeConfig.responseType)
        };
        throw err;
      }
      return {
        data: parseBody(res.body, res.headers, nativeConfig.responseType),
        status: res.status,
        statusText: res.statusText || '',
        headers: res.headers || {},
        config: config
      };
    });
  }

  function axios(config) {
    return request(config || {});
  }

  ['get', 'delete', 'head', 'options'].forEach(function (m) {
    axios[m] = function (url, config) {
      return request(Object.assign({}, config, { method: m, url: url }));
    };
  });

  ['post', 'put', 'patch'].forEach(function (m) {
    axios[m] = function (url, data, config) {
      return request(Object.assign({}, config, { method: m, url: url, data: data }));
    };
  });

  axios.create = function () { return axios; };
  axios.defaults = { headers: {} };

  module.exports = { __esModule: true, default: axios };
})(module, module.exports, require);
