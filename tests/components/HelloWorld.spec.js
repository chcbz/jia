import { render } from '@testing-library/vue'
import HelloWorld from '../../src/components/HelloWorld.vue'
import { expect } from 'chai'
import { cleanup } from '../setup.js'

describe('HelloWorld', () => {
  afterEach(() => {
    cleanup()
  })

  it('renders the component with default message', async () => {
    const { getByText } = render(HelloWorld)
    
    // Check if the component renders with default message
    expect(getByText('Welcome to Your Vue.js App')).to.exist
  })

  it('renders essential links section', async () => {
    const { getByText } = render(HelloWorld)
    
    // Check if essential links section is rendered
    expect(getByText('Essential Links')).to.exist
    expect(getByText('Core Docs')).to.exist
  })

  it('renders ecosystem section', async () => {
    const { getByText } = render(HelloWorld)
    
    // Check if ecosystem section is rendered
    expect(getByText('Ecosystem')).to.exist
    expect(getByText('vue-router')).to.exist
  })
})
