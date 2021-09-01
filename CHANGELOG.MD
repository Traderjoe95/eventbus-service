# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.1] - 2021-09-01
### Fixed
* Properly extract type arguments from all parameters, including lambdas
* Remove debug `println`s

## [1.2.0] - 2021-08-31
### Added
* Support for generic service functions
  * supports type parameters in service function delacrations
  * supports type variables in parameters and return type (direct and as type parameters)
  * supports type variables for vararg
* Dedicated support for lambda parameters to service functions, including suspending lambdas
  * supports extension lambdas (lambdas that have a receiver type)
  * supports type variables for lambdas
### Changed
* Updated to Kotlin 1.5.30
* Updated to Vert.x 4.1.2

## [1.0.2] - 2021-06-11
### Changed
* Updated to Java 16 and Gradle 7.0

## [1.0.1] - 2021-05-05
### Changed
* Updated to Kotlin 1.5.0

## [1.0.0] - 2021-05-05
Base version, implement main functionality
