import { expect } from 'chai'

describe('Basic Mocha Test', () => {
  it('should work with basic assertions', () => {
    expect(true).to.be.true
    expect(1 + 1).to.equal(2)
  })

  it('should work with objects', () => {
    const obj = { name: 'test', value: 42 }
    expect(obj).to.have.property('name')
    expect(obj.name).to.equal('test')
  })
})
