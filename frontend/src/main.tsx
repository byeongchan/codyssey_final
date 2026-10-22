import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import LiveApp from './LiveApp'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {import.meta.env.VITE_DATA_MODE === 'fixture' ? <App /> : <LiveApp />}
  </React.StrictMode>,
)
