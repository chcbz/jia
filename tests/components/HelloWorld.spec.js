import { render } from '@testing-library/vue'
import HelloWorld from '../../src/components/HelloWorld.vue'

describe('HelloWorld', () => {
  it('renders the component with default message', async () => {
    const { getByText } = render(HelloWorld)
    
    // Check if the component renders with default message
    expect(getByText('Welcome to Your Vue.js App')).toBeInTheDocument()
  })

  it('renders essential links section', async () => {
    const { getByText } = render(HelloWorld)
    
    // Check if essential links section is rendered
    expect(getByText('Essential Links')).toBeInTheDocument()
    expect(getByText('Core Docs')).toBeInTheDocument()
  })

  it('renders ecosystem section', async () => {
    const { getByText } = render(HelloWorld)
    
    // Check if ecosystem section is rendered
    expect(getByText('Ecosystem')).toBeInTheDocument()
    expect(getByText('vue-router')).toBeInTheDocument()
  })
})
