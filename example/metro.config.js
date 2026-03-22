const path = require('path');
const { getDefaultConfig } = require('@react-native/metro-config');
const pkg = require('../package.json');
const libPkg = require('../package.json');

const projectRoot = __dirname;
const monorepoRoot = path.resolve(__dirname, '..');
const baseConfig = getDefaultConfig(projectRoot);
const packageName = libPkg.name;
const baseResolveRequest = baseConfig.resolver?.resolveRequest;

const peerDeps = Object.keys(libPkg.peerDependencies || {});

const extraNodeModules = peerDeps.reduce((acc, dep) => {
  acc[dep] = path.join(projectRoot, 'node_modules', dep);
  return acc;
}, { [packageName]: monorepoRoot });

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const blockList = peerDeps.map(
  (dep) => new RegExp(`^${escapeRegExp(path.join(monorepoRoot, 'node_modules', dep))}[\\/\\\\]`)
);

/**
 * Metro configuration
 * https://facebook.github.io/metro/docs/configuration
 *
 * @type {import('metro-config').MetroConfig}
 */
const config = {
  ...baseConfig,
  projectRoot,
  watchFolders: [monorepoRoot],
  resolver: {
    ...baseConfig.resolver,
    extraNodeModules,
    blockList: [
      ...(Array.isArray(baseConfig.resolver?.blockList) ? baseConfig.resolver.blockList : []),
      ...blockList,
    ],
    resolveRequest: (originalContext, moduleName, platform) => {
      let context = originalContext;

      if (moduleName === packageName || moduleName.startsWith(`${packageName}/`)) {
        context = {
          ...context,
          mainFields: ['source', ...(context.mainFields ?? [])],
          unstable_conditionNames: ['source', ...(context.unstable_conditionNames ?? [])],
        };
      }

      if (typeof baseResolveRequest === 'function') {
        return baseResolveRequest(context, moduleName, platform);
      }

      return context.resolveRequest(context, moduleName, platform);
    },
  },
};

module.exports = config;
