# Bajtek

Binary serialization library with S-expression schemas, canonical encoding, and multi-language codegen.

# Initial design of the language

```lisp
(module prelude
  (type u8  #intrinsic) (type s8  #intrinsic)
  (type u16 #intrinsic) (type s16 #intrinsic)
  (type u32 #intrinsic) (type s32 #intrinsic)
  (type u64 #intrinsic) (type s64 #intrinsic)

  (type f32 #intrinsic)
  (type f64 #intrinsic)

  (type bool #intrinsic)
  (type string #intrinsic)

  (type (list T) #intrinsic)
  (type (array T N) #intrinsic)
)
```

```lisp
(module geometry
  (type point2d
    (struct
      (x f64)
      (y f64)))

  (type point3d
    (struct
      (x f64)
      (y f64)
      (z f64)))

  (type point (union point2d point3d))
)
```
