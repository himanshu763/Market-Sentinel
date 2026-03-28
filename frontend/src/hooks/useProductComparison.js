import { useState } from 'react';

// In production: VITE_API_URL is "" (empty) → relative URL → Nginx proxies /api/* to backend
// In local dev:  set VITE_API_URL=http://localhost:8080 in frontend/.env.local
const API_BASE = import.meta.env.VITE_API_URL || '';

export const useProductComparison = () => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchComparison = async (url) => {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`${API_BASE}/api/compare`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url }),
      });

      if (!response.ok) throw new Error('Server expansion failed');

      const result = await response.json();
      setData(result);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return { data, loading, error, fetchComparison };
};
