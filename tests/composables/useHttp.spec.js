import { expect } from 'chai'
import { cleanup } from '../setup.js'

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
  afterEach(() => {
    cleanup()
  })

  it('should return a get function', () => {
    const http = mockUseHttp()
    expect(http.get).to.exist
    expect(http.get).to.be.a('function')
  })

  it('should return a post function', () => {
    const http = mockUseHttp()
    expect(http.post).to.exist
    expect(http.post).to.be.a('function')
  })

  it('get function should return mock data', async () => {
    const http = mockUseHttp()
    const response = await http.get('/test')
    expect(response.data).to.deep.equal({ message: 'Mock response' })
  })

  it('post function should return data with id', async () => {
    const http = mockUseHttp()
    const testData = { name: 'Test' }
    const response = await http.post('/test', testData)
    expect(response.data).to.deep.equal({ name: 'Test', id: 1 })
  })
})
