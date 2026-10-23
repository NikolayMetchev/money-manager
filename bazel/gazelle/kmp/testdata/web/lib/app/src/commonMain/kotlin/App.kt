package com.example.app

import kotlinx.coroutines.flow.Flow

expect fun names(): Flow<String>
