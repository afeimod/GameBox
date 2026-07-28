/**
 * RetroBox - Gitee 游戏列表拉取与下载脚本
 *
 * 基于 Gitee API v5，通过 Gitee 仓库托管游戏 ROM 与游戏列表。
 * 所有函数均返回 Promise，可在 Node.js 或支持 fetch 的环境运行。
 *
 * 相关接口：
 *   列表: GET https://gitee.com/api/v5/repos/{owner}/{repo}/contents/{path}
 *   下载: GET https://gitee.com/{owner}/{repo}/raw/{branch}/{path}
 */

// ===== 配置项 =====
const CONFIG = {
  // Gitee 仓库拥有者（用户名或组织名）
  GITEE_OWNER: 'your-gitee-owner',
  // 仓库名
  GITEE_REPO: 'roms-repo',
  // 分支名
  GITEE_BRANCH: 'master',
  // 私有令牌（可选；私有仓库或避免接口限流时填写）
  GITEE_TOKEN: '',
  // API 基地址
  API_BASE: 'https://gitee.com/api/v5',
  // 原始文件下载基地址
  RAW_BASE: 'https://gitee.com'
};

// 平台 -> 仓库目录映射
const PLATFORM_DIRS = {
  FC: 'fc',
  SFC: 'sfc',
  MD: 'md',
  ARCADE: 'arcade'
};

// 平台 -> 支持的 ROM 扩展名（用于过滤目录中的非 ROM 文件）
const PLATFORM_EXT = {
  FC: ['nes', 'fds', 'unf', 'nez'],
  SFC: ['smc', 'sfc', 'fig', 'bs'],
  MD: ['md', 'gen', 'smd', 'bin'],
  ARCADE: ['zip', '7z']
};

/**
 * 构造带鉴权的请求头
 */
function authHeaders() {
  const headers = { 'Accept': 'application/json' };
  if (CONFIG.GITEE_TOKEN) {
    headers['Authorization'] = `token ${CONFIG.GITEE_TOKEN}`;
  }
  return headers;
}

/**
 * 拼接 query string
 */
function buildUrl(base, params) {
  const cleaned = {};
  Object.keys(params || {}).forEach(k => {
    if (params[k] !== undefined && params[k] !== null && params[k] !== '') {
      cleaned[k] = params[k];
    }
  });
  const qs = new URLSearchParams(cleaned).toString();
  return qs ? `${base}?${qs}` : base;
}

/**
 * 构造原始文件下载 URL
 */
function buildRawUrl(remotePath) {
  const safePath = String(remotePath || '').replace(/^\/+/, '');
  return `${CONFIG.RAW_BASE}/${CONFIG.GITEE_OWNER}/${CONFIG.GITEE_REPO}/raw/${CONFIG.GITEE_BRANCH}/${safePath}`;
}

/**
 * 判断文件是否属于指定平台的 ROM
 */
function isRomFile(fileName, platform) {
  const exts = PLATFORM_EXT[platform] || [];
  const ext = fileName.split('.').pop().toLowerCase();
  return exts.indexOf(ext) !== -1;
}

/**
 * 获取指定平台的游戏列表
 *
 * @param {string} platform 平台标识：FC / SFC / MD / ARCADE
 * @returns {Promise<Array>} 游戏信息数组
 */
function fetchGameList(platform) {
  return new Promise((resolve, reject) => {
    const dir = PLATFORM_DIRS[platform];
    if (!dir) {
      return reject(new Error(`不支持的平台: ${platform}`));
    }
    const url = buildUrl(
      `${CONFIG.API_BASE}/repos/${CONFIG.GITEE_OWNER}/${CONFIG.GITEE_REPO}/contents/${dir}`,
      { ref: CONFIG.GITEE_BRANCH, per_page: 100 }
    );
    fetch(url, { headers: authHeaders() })
      .then(res => {
        if (!res.ok) throw new Error(`获取列表失败: HTTP ${res.status}`);
        return res.json();
      })
      .then(items => {
        const games = (items || [])
          .filter(it => it.type === 'file' && isRomFile(it.name, platform))
          .map(it => ({
            name: it.name.replace(/\.[^.]+$/, ''),
            fileName: it.name,
            platform: platform,
            fileSize: it.size || 0,
            downloadUrl: it.download_url || buildRawUrl(it.path),
            coverUrl: '',
            description: '',
            romUrl: it.path
          }));
        resolve(games);
      })
      .catch(reject);
  });
}

/**
 * 下载游戏 ROM 到指定路径
 *
 * @param {object} gameInfo 游戏信息（需包含 downloadUrl 或 romUrl）
 * @param {string} destPath 本地保存路径
 * @returns {Promise<string|object>} Node 环境返回保存路径；浏览器环境返回 { path, buffer }
 */
function downloadGame(gameInfo, destPath) {
  return new Promise((resolve, reject) => {
    const url = gameInfo.downloadUrl || buildRawUrl(gameInfo.romUrl);
    fetch(url, { headers: authHeaders() })
      .then(res => {
        if (!res.ok) throw new Error(`下载失败: HTTP ${res.status}`);
        return res.arrayBuffer();
      })
      .then(buf => {
        // Node 环境：直接写入文件
        if (typeof require === 'function') {
          try {
            const fs = require('fs');
            const path = require('path');
            fs.mkdirSync(path.dirname(destPath), { recursive: true });
            fs.writeFileSync(destPath, Buffer.from(buf));
            resolve(destPath);
          } catch (e) {
            reject(e);
          }
        } else {
          // 浏览器环境：返回 Buffer 供调用方自行处理
          resolve({ path: destPath, buffer: buf });
        }
      })
      .catch(reject);
  });
}

/**
 * 搜索游戏（按名称关键字，跨所有平台）
 *
 * @param {string} keyword 关键字
 * @returns {Promise<Array>} 匹配的游戏列表
 */
function searchGames(keyword) {
  return new Promise((resolve, reject) => {
    const kw = String(keyword || '').toLowerCase();
    const platforms = Object.keys(PLATFORM_DIRS);
    Promise.all(platforms.map(p => fetchGameList(p).catch(() => [])))
      .then(results => {
        const all = results.reduce((acc, cur) => acc.concat(cur), []);
        if (!kw) {
          resolve(all);
        } else {
          resolve(all.filter(g => g.name.toLowerCase().indexOf(kw) !== -1));
        }
      })
      .catch(reject);
  });
}

/**
 * 获取分类（平台）列表
 *
 * @returns {Promise<Array>} 平台标识数组
 */
function getCategories() {
  return Promise.resolve(Object.keys(PLATFORM_DIRS));
}

// ===== 模块导出（Node 环境可用 require 引入） =====
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    CONFIG,
    PLATFORM_DIRS,
    fetchGameList,
    downloadGame,
    searchGames,
    getCategories,
    buildRawUrl
  };
}
