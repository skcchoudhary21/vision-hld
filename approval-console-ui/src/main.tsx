import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { theme } from './theme';
import { ActorProvider } from './state/ActorContext';
import App from './App.tsx';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <ActorProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </ActorProvider>
    </ThemeProvider>
  </StrictMode>,
);
