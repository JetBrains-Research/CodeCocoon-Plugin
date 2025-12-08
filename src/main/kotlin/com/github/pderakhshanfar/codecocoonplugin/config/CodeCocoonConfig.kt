package com.github.pderakhshanfar.codecocoonplugin.config

/**
 * Top-level configuration for CodeCocoon, loaded from YAML.
 */
 data class CodeCocoonConfig(
     /** Absolute or project-local path to the root of the project to transform */
     val projectRoot: String? = null,
     /** Optional list of files relative to the project root. Empty means the entire project */
     val files: List<String> = emptyList(),
     /** Ordered list of transformations to execute */
     val transformations: List<TransformationConfig> = emptyList(),
 )

 /**
  * Single transformation entry. `config` is free-form and parsed by the transformation itself.
  */
 data class TransformationConfig(
     val id: String,
     val config: Map<String, Any> = emptyMap(),
 )
