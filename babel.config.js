module.exports = function getBabelConfig(api) {
  const isMetro = api.caller((caller) => caller?.name === 'metro');

  // Metro bundle should use RN preset so codegenNativeComponent static transform runs.
  if (isMetro) {
    return {
      presets: ['module:@react-native/babel-preset'],
    };
  }

  return {
    overrides: [
      {
        exclude: /\/node_modules\//,
        presets: ['module:react-native-builder-bob/babel-preset'],
      },
      {
        include: /\/node_modules\//,
        presets: ['module:@react-native/babel-preset'],
      },
    ],
  };
};
