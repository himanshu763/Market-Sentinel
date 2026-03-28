import { useState } from 'react';

export const useProductComparison = () => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchComparison = async (url) => {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch('http://localhost:8080/api/compare', {
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