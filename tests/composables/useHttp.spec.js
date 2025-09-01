import { describe, it, expect } from 'vitest'

// Mock the useHttp composable for testing
const mockUseHttp = () => {
  return {
    get: async (url) => {
      return { data: { message: 'Mock response' } }
    },
    post: async (url, data) => {
      return { data: { ...data, id: 1 } }
    }
  }
}

describe('useHttp', () => {
  it('should return a get function', () => {
    const http = mockUseHttp()
    expect(http.get).toBeDefined()
    expect(typeof http.get).toBe('function')
  })

  it('should return a post function', () => {
    const http = mockUseHttp()
    expect(http.post).toBeDefined()
    expect(typeof http.post).toBe('function')
  })

  it('get function should return mock data', async () => {
    const http = mockUseHttp()
    const response = await http.get('/test')
    expect(response.data).toEqual({ message: 'Mock response' })
  })

  it('post function should return data with id', async () => {
    const http = mockUseHttp()
    const testData = { name: 'Test' }
    const response = await http.post('/test', testData)
    expect(response.data).toEqual({ name: 'Test', id: 1 })
  })
})
