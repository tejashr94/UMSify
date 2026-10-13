package com.umsify.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a registered UMSify user entity.
 * Stored locally after successful login/signup.
 * Password hash is NEVER stored locally.
 */
@Entity(tableName = "users")
data class User(
    @PrimaryKey
    val userId: String,
    val name: String,
    val registrationNumber: String,
    val username: String,
    val section: String = "",
    val gender: String = "",
    val email: String = "",
    val accountStatus: String = "ACTIVE",
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastLogin: String = ""
)

typealias UserEntity = User
