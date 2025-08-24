import { defineStore } from 'pinia'
import { useUtilStore } from './util'
import { useGlobalStore } from './global'

// PKCE工具方法
function generateRandomString(length) {
  const charset = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~'
  let result = ''
  const crypto = window.crypto || window.msCrypto
  const values = new Uint8Array(length)
  crypto.getRandomValues(values)
  for (let i = 0; i < length; i++) {
    result += charset[values[i] % charset.length]
  }
  return result
}

async function generateCodeChallenge(codeVerifier) {
  const encoder = new TextEncoder()
  const data = encoder.encode(codeVerifier)
  const digest = await window.crypto.subtle.digest('SHA-256', data)
  return btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

export const useApiStore = defineStore('api', {
  state: () => ({
    baseUrl: import.meta.env.VITE_API_BASE_URL,
    dwzDomain: import.meta.env.VITE_DWZ_DOMAIN
  }),
  actions: {
    async token() {
      const utilStore = useUtilStore()
      const globalStore = useGlobalStore()

      let accessToken = utilStore.getLocalStorage('api_token')
      if (!accessToken) {
        // if (globalStore.getOpenid) {
        //   // 保留原有密码模式作为fallback
        //   const xhr = new XMLHttpRequest()
        //   xhr.open('POST', `${this.baseUrl}/oauth2/token?grant_type=password&username=wx-${globalStore.getOpenid}&password=wxpwd`, false)
        //   xhr.setRequestHeader('Content-Type', 'application/json')
        //   xhr.setRequestHeader('Authorization', 'Basic amlhX2NsaWVudDpqaWFfc2VjcmV0')
        //   xhr.send(null)
        //   const data = JSON.parse(xhr.responseText)
        //   accessToken = data.access_token
        //   utilStore.setLocalStorage('api_token', accessToken, new Date().getTime() + data.expires_in * 1000 - 60000)
        // } else {
          // PKCE流程
          const codeVerifier = generateRandomString(64)
          const codeChallenge = await generateCodeChallenge(codeVerifier)
          utilStore.setLocalStorage('pkce_code_verifier', codeVerifier)

          const params = new URLSearchParams({
            response_type: 'code',
            client_id: import.meta.env.VITE_OAUTH_CLIENT_ID,
            scope: 'openid',
            redirect_uri: window.location.origin + '/oauth2/callback',
            code_challenge: codeChallenge,
            code_challenge_method: 'S256',
            state: window.location.pathname,
            access_type: 'offline'
          })

          window.location.href = `${this.baseUrl}/oauth2/authorize?${params.toString()}`
        // }
      }
      return accessToken
    },

    async exchangeCodeForToken(code) {
      const utilStore = useUtilStore()
      const codeVerifier = utilStore.getLocalStorage('pkce_code_verifier')
      if (!codeVerifier) throw new Error('No code verifier found')

      const params = new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        redirect_uri: window.location.origin + '/oauth2/callback',
        client_id: import.meta.env.VITE_OAUTH_CLIENT_ID,
        code_verifier: codeVerifier
      })
      const response = await fetch(`${this.baseUrl}/oauth2/token`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        body: params
      })

      if (!response.ok) throw new Error('Token exchange failed')

      const data = await response.json()
      console.log('Token exchange successful:', data)
      utilStore.removeLocalStorage('pkce_code_verifier')
      utilStore.setLocalStorage('api_token', data.access_token, new Date().getTime() + data.expires_in * 1000 - 60000)
      return data.access_token
    },
    wxJsToken(url) {
      const utilStore = useUtilStore()
      const globalStore = useGlobalStore()

      const wxJsTokenKey = `wx_js_token_${utilStore.getHashCode(url)}`
      let accessToken = utilStore.getLocalStorage(wxJsTokenKey)
      if (!accessToken) {
        const xhr = new XMLHttpRequest()
        xhr.open('GET', `${this.baseUrl}/wx/mp/jsapi/signature?appid=${globalStore.user.appid}&url=${url}`, false)
        xhr.setRequestHeader('Content-Type', 'application/json')
        xhr.send(null)
        const data = JSON.parse(xhr.responseText)
        if (data && data.data) {
          accessToken = data.data
          utilStore.setLocalStorage(wxJsTokenKey, accessToken, new Date().getTime() + 6000000)
        }
      }
      return accessToken
    },

    cleanToken() {
      const utilStore = useUtilStore()
      utilStore.removeLocalStorage('api_token')
    },

    async getUserInfo() {
      const token = await this.token()
      const globalStore = useGlobalStore()
      
      const response = await fetch(`${this.baseUrl}/user/my`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      })

      if (!response.ok) throw new Error('Failed to get user info')

      const result = await response.json()
      console.log('User info retrieved:', result)
      const data = result.data
      if (data.jiacn) {
        globalStore.setJiacn(data.jiacn)
      }
      if (data.openid) {
        globalStore.setOpenid(data.openid)
      }
      return data
    }
  }
})
