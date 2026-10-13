package com.umsify.app.ui.auth

import androidx.lifecycle.*
import com.umsify.app.data.model.UserSession
import com.umsify.app.data.repository.AuthRepository
import com.umsify.app.data.repository.AuthResult
import kotlinx.coroutines.launch

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _authSuccess = MutableLiveData<UserSession?>()
    val authSuccess: LiveData<UserSession?> = _authSuccess

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _welcomeMessage = MutableLiveData<String?>()
    val welcomeMessage: LiveData<String?> = _welcomeMessage

    private val _isSignupFlow = MutableLiveData(false)
    val isSignupFlow: LiveData<Boolean> = _isSignupFlow

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _isSignupFlow.value = false
            when (val result = repository.login(username, password)) {
                is AuthResult.Success -> {
                    _authSuccess.value = result.session
                }
                is AuthResult.Error -> {
                    _errorMessage.value = result.message
                }
                is AuthResult.NetworkError -> {
                    _errorMessage.value = "No internet connection. Please check your network."
                }
            }
            _isLoading.value = false
        }
    }

    fun signup(
        name: String,
        registrationNumber: String,
        username: String,
        password: String,
        gender: String,
        email: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _isSignupFlow.value = true
            when (val result = repository.signup(name, registrationNumber, username, password, gender, email)) {
                is AuthResult.Success -> {
                    _welcomeMessage.value = "Account created successfully"
                    _authSuccess.value = result.session
                }
                is AuthResult.Error -> {
                    _errorMessage.value = result.message
                }
                is AuthResult.NetworkError -> {
                    _errorMessage.value = "No internet connection. Please check your network."
                }
            }
            _isLoading.value = false
        }
    }
}

class AuthViewModelFactory(private val repository: AuthRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AuthViewModel(repository) as T
    }
}
