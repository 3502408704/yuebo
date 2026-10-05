(function (module, exports, require) {
  'use strict';

  // Buffer 兼容层（月播最终版）：覆盖音乐源插件的常见用法——
  // Buffer.from(string|数组|ArrayBuffer[, encoding])、Buffer.alloc、Buffer.isBuffer、
  // Buffer.concat、toString(utf8/base64/hex/latin1)、length、slice/subarray、
  // 读写整数（readUInt32BE 族常用子集）。纯 JS 实现，不依赖宿主。

  function utf8Encode(str) {
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var code = str.codePointAt(i);
      if (code > 0xffff) i++; // 代理对
      if (code < 0x80) {
        out.push(code);
      } else if (code < 0x800) {
        out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
      } else if (code < 0x10000) {
        out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      } else {
        out.push(
          0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f),
          0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      }
    }
    return out;
  }

  function utf8Decode(bytes, start, end) {
    var out = '';
    var i = start;
    while (i < end) {
      var b = bytes[i];
      var code;
      if (b < 0x80) {
        code = b; i += 1;
      } else if ((b & 0xe0) === 0xc0 && i + 1 < end) {
        code = ((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f); i += 2;
      } else if ((b & 0xf0) === 0xe0 && i + 2 < end) {
        code = ((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f); i += 3;
      } else if ((b & 0xf8) === 0xf0 && i + 3 < end) {
        code = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) |
          ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f); i += 4;
      } else {
        code = 0xfffd; i += 1;
      }
      if (code > 0xffff) {
        code -= 0x10000;
        out += String.fromCharCode(0xd800 + (code >> 10), 0xdc00 + (code & 0x3ff));
      } else {
        out += String.fromCharCode(code);
      }
    }
    return out;
  }

  var B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

  function base64Encode(bytes, start, end) {
    var out = '';
    for (var i = start; i < end; i += 3) {
      var b1 = bytes[i];
      var b2 = i + 1 < end ? bytes[i + 1] : 0;
      var b3 = i + 2 < end ? bytes[i + 2] : 0;
      out += B64_CHARS.charAt(b1 >> 2);
      out += B64_CHARS.charAt(((b1 & 3) << 4) | (b2 >> 4));
      out += i + 1 < end ? B64_CHARS.charAt(((b2 & 15) << 2) | (b3 >> 6)) : '=';
      out += i + 2 < end ? B64_CHARS.charAt(b3 & 63) : '=';
    }
    return out;
  }

  var B64_LOOKUP = (function () {
    var lookup = {};
    for (var i = 0; i < B64_CHARS.length; i++) lookup[B64_CHARS.charAt(i)] = i;
    lookup['-'] = 62; // base64url 容忍
    lookup['_'] = 63;
    return lookup;
  })();

  function base64Decode(str) {
    var clean = str.replace(/[^A-Za-z0-9+/\-_]/g, '');
    var out = [];
    for (var i = 0; i < clean.length; i += 4) {
      var n = (B64_LOOKUP[clean.charAt(i)] << 18) |
        (B64_LOOKUP[clean.charAt(i + 1)] << 12) |
        ((B64_LOOKUP[clean.charAt(i + 2)] || 0) << 6) |
        (B64_LOOKUP[clean.charAt(i + 3)] || 0);
      out.push((n >> 16) & 0xff);
      if (clean.charAt(i + 2)) out.push((n >> 8) & 0xff);
      if (clean.charAt(i + 3)) out.push(n & 0xff);
    }
    return out;
  }

  var HEX_CHARS = '0123456789abcdef';

  function hexEncode(bytes, start, end) {
    var out = '';
    for (var i = start; i < end; i++) {
      out += HEX_CHARS.charAt(bytes[i] >> 4) + HEX_CHARS.charAt(bytes[i] & 15);
    }
    return out;
  }

  function hexDecode(str) {
    var clean = str.replace(/[^0-9a-fA-F]/g, '');
    if (clean.length % 2 === 1) clean = '0' + clean;
    var out = [];
    for (var i = 0; i < clean.length; i += 2) {
      out.push(parseInt(clean.substr(i, 2), 16));
    }
    return out;
  }

  function latin1Decode(bytes, start, end) {
    var out = '';
    for (var i = start; i < end; i++) out += String.fromCharCode(bytes[i] & 0xff);
    return out;
  }

  function toBytes(value, encoding) {
    if (value === null || value === undefined) return [];
    if (typeof value === 'string') {
      var enc = (encoding || 'utf8').toLowerCase().replace('-', '');
      if (enc === 'base64') return base64Decode(value);
      if (enc === 'hex') return hexDecode(value);
      if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') {
        var lat = [];
        for (var i = 0; i < value.length; i++) lat.push(value.charCodeAt(i) & 0xff);
        return lat;
      }
      return utf8Encode(value); // utf8/utf-8 及未知编码一律按 utf8
    }
    if (typeof value.length === 'number') {
      var arr = [];
      for (var j = 0; j < value.length; j++) arr.push(value[j] & 0xff);
      return arr;
    }
    if (typeof value === 'object' && typeof value.byteLength === 'number') {
      return Array.prototype.slice.call(new Uint8Array(value));
    }
    return [];
  }

  function Buffer(arg, encodingOrOffset) {
    if (!(this instanceof Buffer)) {
      return new Buffer(arg, encodingOrOffset);
    }
    if (typeof arg === 'number') {
      this._bytes = new Array(arg).fill(0);
    } else {
      this._bytes = toBytes(arg, encodingOrOffset);
    }
    this.length = this._bytes.length;
  }

  Buffer.isBuffer = function (value) { return value instanceof Buffer; };

  Buffer.from = function (value, encodingOrOffset) { return new Buffer(value, encodingOrOffset); };

  Buffer.alloc = function (size, fill) {
    var buf = new Buffer(size);
    if (fill !== undefined && fill !== 0) {
      var fillBytes = toBytes(fill);
      for (var i = 0; i < size; i++) buf._bytes[i] = fillBytes[i % fillBytes.length];
    }
    return buf;
  };

  Buffer.concat = function (list) {
    var total = 0;
    for (var i = 0; i < list.length; i++) total += list[i].length;
    var out = new Buffer(total);
    var offset = 0;
    for (var j = 0; j < list.length; j++) {
      var part = list[j];
      for (var k = 0; k < part.length; k++) out._bytes[offset + k] = part._bytes[k];
      offset += part.length;
    }
    return out;
  };

  Buffer.prototype.toString = function (encoding, start, end) {
    var enc = (encoding || 'utf8').toLowerCase().replace('-', '');
    var s = start === undefined ? 0 : start;
    var e = end === undefined ? this.length : end;
    if (enc === 'base64') return base64Encode(this._bytes, s, e);
    if (enc === 'hex') return hexEncode(this._bytes, s, e);
    if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') return latin1Decode(this._bytes, s, e);
    return utf8Decode(this._bytes, s, e);
  };

  Buffer.prototype.slice = function (start, end) {
    var s = start === undefined ? 0 : (start < 0 ? this.length + start : start);
    var e = end === undefined ? this.length : (end < 0 ? this.length + end : end);
    var out = new Buffer(0);
    out._bytes = this._bytes.slice(s, e);
    out.length = out._bytes.length;
    return out;
  };
  Buffer.prototype.subarray = Buffer.prototype.slice;

  Buffer.prototype.toJSON = function () {
    return { type: 'Buffer', data: this._bytes.slice() };
  };

  function defineReaders(bitWidth, signed) {
    var maxValue = Math.pow(2, bitWidth) - 1;
    ['BE', 'LE'].forEach(function (order) {
      var suffix = 'UInt' + bitWidth + order;
      Buffer.prototype['read' + suffix] = function (offset) {
        var value = 0;
        if (order === 'BE') {
          for (var i = 0; i < bitWidth / 8; i++) value = value * 256 + (this._bytes[offset + i] & 0xff);
        } else {
          for (var j = bitWidth / 8 - 1; j >= 0; j--) value = value * 256 + (this._bytes[offset + j] & 0xff);
        }
        return signed && value > maxValue / 2 ? value - maxValue - 1 : value;
      };
      Buffer.prototype['write' + (signed ? 'Int' : 'UInt') + bitWidth + order] = function (value, offset) {
        for (var i = 0; i < bitWidth / 8; i++) {
          var shift = order === 'BE' ? (bitWidth / 8 - 1 - i) : i;
          this._bytes[offset + i] = Math.floor(value / Math.pow(256, shift)) & 0xff;
        }
        return offset + bitWidth / 8;
      };
    });
  }
  defineReaders(16, true);
  defineReaders(32, true);

  Buffer.prototype.indexOf = function (value) {
    var needle = Buffer.isBuffer(value) ? value._bytes : toBytes(value);
    outer: for (var i = 0; i <= this.length - needle.length; i++) {
      for (var j = 0; j < needle.length; j++) {
        if (this._bytes[i + j] !== needle[j]) continue outer;
      }
      return i;
    }
    return -1;
  };

  module.exports = Buffer;
  module.exports.default = Buffer;
  module.exports.Buffer = Buffer;
})(module, module.exports, require);
