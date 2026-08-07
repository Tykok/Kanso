package dev.kanso.service

class NotFoundException(message: String) : RuntimeException(message)

/** The request is well-formed but conflicts with the current state (duplicate key, cycle). */
class ConflictException(message: String) : RuntimeException(message)

/** The request is understandable but wrong (unknown status, missing team). */
class BadRequestException(message: String) : RuntimeException(message)
