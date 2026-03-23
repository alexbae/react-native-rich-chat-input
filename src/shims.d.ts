// react-native/Libraries/Types/CodegenTypes has no bundled type declarations.
// Re-export from the available CodegenTypesNamespace.d.ts shim.
declare module 'react-native/Libraries/Types/CodegenTypes' {
  export {
    BubblingEventHandler,
    DirectEventHandler,
    Double,
    Float,
    Int32,
    UnsafeObject,
    UnsafeMixed,
    WithDefault,
    EventEmitter,
  } from 'react-native/Libraries/Types/CodegenTypesNamespace';
}
