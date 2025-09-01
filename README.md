# cyf-web-kit

> A Vue.js project

## Build Setup

``` bash
# install dependencies
npm install

# serve with hot reload at localhost:8080
npm run dev

# build for production with minification
npm run build

# build for production and view the bundle analyzer report
npm run build --report
```

## Testing

This project uses [Vitest](https://vitest.dev/) for unit testing with [Vue Testing Library](https://testing-library.com/docs/vue-testing-library/intro/).

### Available Test Scripts

```bash
# Run tests in watch mode
npm run test

# Run tests once and exit
npm run test:run

# Run tests with UI interface
npm run test:ui
```

### Writing Tests

Tests are located in the `tests/` directory:
- Component tests: `tests/components/`
- Composable/utility tests: `tests/composables/`

### Example Test Structure

```javascript
import { render } from '@testing-library/vue'
import MyComponent from '@/components/MyComponent.vue'

describe('MyComponent', () => {
  it('renders correctly', async () => {
    const { getByText } = render(MyComponent)
    expect(getByText('Expected Text')).toBeInTheDocument()
  })
})
```

For a detailed explanation on how things work, check out the [guide](http://vuejs-templates.github.io/webpack/) and [docs for vue-loader](http://vuejs.github.io/vue-loader).
